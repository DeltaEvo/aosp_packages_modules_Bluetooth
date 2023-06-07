/******************************************************************************
 *
 *  Copyright 2009-2012 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#include "bta_hh_co.h"

#include <base/logging.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/uhid.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "bta_api.h"
#include "bta_hh_api.h"
#include "btif_hh.h"
#include "btif_util.h"
#include "device/include/controller.h"
#include "osi/include/allocator.h"
#include "osi/include/compat.h"
#include "osi/include/osi.h"
#include "types/raw_address.h"

const char* dev_path = "/dev/uhid";

#include "btif_config.h"
#define BTA_HH_NV_LOAD_MAX 16
static tBTA_HH_RPT_CACHE_ENTRY sReportCache[BTA_HH_NV_LOAD_MAX];
#define BTA_HH_CACHE_REPORT_VERSION 1
#define THREAD_NORMAL_PRIORITY 0
#define BT_HH_THREAD "bt_hh_thread"
#define BTA_HH_UHID_POLL_PERIOD_MS 50
/* Max number of polling interrupt allowed */
#define BTA_HH_UHID_INTERRUPT_COUNT_MAX 100

static const bthh_report_type_t map_rtype_uhid_hh[] = {
    BTHH_FEATURE_REPORT, BTHH_OUTPUT_REPORT, BTHH_INPUT_REPORT};

static void* btif_hh_poll_event_thread(void* arg);

void uhid_set_non_blocking(int fd) {
  int opts = fcntl(fd, F_GETFL);
  if (opts < 0)
    APPL_TRACE_ERROR("%s() Getting flags failed (%s)", __func__,
                     strerror(errno));

  opts |= O_NONBLOCK;

  if (fcntl(fd, F_SETFL, opts) < 0)
    APPL_TRACE_EVENT("%s() Setting non-blocking flag failed (%s)", __func__,
                     strerror(errno));
}

static bool uhid_feature_req_handler(btif_hh_device_t* p_dev,
                                     struct uhid_feature_req& req) {
  LOG_DEBUG("Report type = %d, id = %d", req.rtype, req.rnum);

  if (req.rtype > UHID_INPUT_REPORT) {
    LOG_ERROR("Invalid report type %d", req.rtype);
    return false;
  }

  if (p_dev->get_rpt_id_queue == nullptr) {
    LOG_ERROR("Queue is not initialized");
    return false;
  }

  uint32_t* context = (uint32_t*)osi_malloc(sizeof(uint32_t));
  *context = req.id;

  if (!fixed_queue_try_enqueue(p_dev->get_rpt_id_queue, (void*)context)) {
    osi_free(context);
    LOG_ERROR("Queue is full, dropping event %d", req.id);
    return false;
  }

  btif_hh_getreport(p_dev, map_rtype_uhid_hh[req.rtype], req.rnum, 0);
  return true;
}

#if ENABLE_UHID_SET_REPORT
static bool uhid_set_report_req_handler(btif_hh_device_t* p_dev,
                                        struct uhid_set_report_req& req) {
  LOG_DEBUG("Report type = %d, id = %d", req.rtype, req.rnum);

  if (req.rtype > UHID_INPUT_REPORT) {
    LOG_ERROR("Invalid report type %d", req.rtype);
    return false;
  }

  if (p_dev->set_rpt_id_queue == nullptr) {
    LOG_ERROR("Queue is not initialized");
    return false;
  }

  uint32_t* context = (uint32_t*)osi_malloc(sizeof(uint32_t));
  *context = req.id;

  if (!fixed_queue_try_enqueue(p_dev->set_rpt_id_queue, (void*)context)) {
    osi_free(context);
    LOG_ERROR("Queue is full, dropping event %d", req.id);
    return false;
  }

  btif_hh_setreport(p_dev, map_rtype_uhid_hh[req.rtype], req.size, req.data);
  return true;
}
#endif  // ENABLE_UHID_SET_REPORT

/*Internal function to perform UHID write and error checking*/
static int uhid_write(int fd, const struct uhid_event* ev) {
  ssize_t ret;
  OSI_NO_INTR(ret = write(fd, ev, sizeof(*ev)));

  if (ret < 0) {
    int rtn = -errno;
    APPL_TRACE_ERROR("%s: Cannot write to uhid:%s", __func__, strerror(errno));
    return rtn;
  } else if (ret != (ssize_t)sizeof(*ev)) {
    APPL_TRACE_ERROR("%s: Wrong size written to uhid: %zd != %zu", __func__,
                     ret, sizeof(*ev));
    return -EFAULT;
  }

  return 0;
}

/* Internal function to parse the events received from UHID driver*/
static int uhid_read_event(btif_hh_device_t* p_dev) {
  CHECK(p_dev);

  struct uhid_event ev;
  memset(&ev, 0, sizeof(ev));

  ssize_t ret;
  OSI_NO_INTR(ret = read(p_dev->fd, &ev, sizeof(ev)));

  if (ret == 0) {
    APPL_TRACE_ERROR("%s: Read HUP on uhid-cdev %s", __func__, strerror(errno));
    return -EFAULT;
  } else if (ret < 0) {
    APPL_TRACE_ERROR("%s: Cannot read uhid-cdev: %s", __func__,
                     strerror(errno));
    return -errno;
  }

  switch (ev.type) {
    case UHID_START:
      APPL_TRACE_DEBUG("UHID_START from uhid-dev\n");
      p_dev->ready_for_data = true;
      break;
    case UHID_STOP:
      APPL_TRACE_DEBUG("UHID_STOP from uhid-dev\n");
      p_dev->ready_for_data = false;
      break;
    case UHID_OPEN:
      APPL_TRACE_DEBUG("UHID_OPEN from uhid-dev\n");
      p_dev->ready_for_data = true;
      break;
    case UHID_CLOSE:
      APPL_TRACE_DEBUG("UHID_CLOSE from uhid-dev\n");
      p_dev->ready_for_data = false;
      break;
    case UHID_OUTPUT:
      if (ret < (ssize_t)(sizeof(ev.type) + sizeof(ev.u.output))) {
        APPL_TRACE_ERROR("%s: Invalid size read from uhid-dev: %zd < %zu",
                         __func__, ret, sizeof(ev.type) + sizeof(ev.u.output));
        return -EFAULT;
      }

      APPL_TRACE_DEBUG("UHID_OUTPUT: Report type = %d, report_size = %d",
                       ev.u.output.rtype, ev.u.output.size);
      // Send SET_REPORT with feature report if the report type in output event
      // is FEATURE
      if (ev.u.output.rtype == UHID_FEATURE_REPORT)
        btif_hh_setreport(p_dev, BTHH_FEATURE_REPORT, ev.u.output.size,
                          ev.u.output.data);
      else if (ev.u.output.rtype == UHID_OUTPUT_REPORT)
        btif_hh_senddata(p_dev, ev.u.output.size, ev.u.output.data);
      else
        APPL_TRACE_ERROR("%s: UHID_OUTPUT: Invalid report type = %d", __func__,
                         ev.u.output.rtype);
      break;
    case UHID_OUTPUT_EV:
      if (ret < (ssize_t)(sizeof(ev.type) + sizeof(ev.u.output_ev))) {
        APPL_TRACE_ERROR("%s: Invalid size read from uhid-dev: %zd < %zu",
                         __func__, ret,
                         sizeof(ev.type) + sizeof(ev.u.output_ev));
        return -EFAULT;
      }
      APPL_TRACE_DEBUG("UHID_OUTPUT_EV from uhid-dev\n");
      break;

    case UHID_FEATURE:  // UHID_GET_REPORT
      if (ret < (ssize_t)(sizeof(ev.type) + sizeof(ev.u.feature))) {
        LOG_ERROR("UHID_GET_REPORT: Invalid size read from uhid-dev: %zd < %zu",
                  ret, sizeof(ev.type) + sizeof(ev.u.feature));
        return -EFAULT;
      }

      if (!uhid_feature_req_handler(p_dev, ev.u.feature)) {
        return -EFAULT;
      }

      break;

#if ENABLE_UHID_SET_REPORT
    case UHID_SET_REPORT: {
      if (ret < (ssize_t)(sizeof(ev.type) + sizeof(ev.u.set_report))) {
        LOG_ERROR("UHID_SET_REPORT: Invalid size read from uhid-dev: %zd < %zu",
                  ret, sizeof(ev.type) + sizeof(ev.u.set_report));
        return -EFAULT;
      }

      if (!uhid_set_report_req_handler(p_dev, ev.u.set_report)) {
        return -EFAULT;
      }
      break;
    }
#endif  // ENABLE_UHID_SET_REPORT

    default:
      LOG_ERROR("Invalid event from uhid-dev: %u\n", ev.type);
  }

  return 0;
}

/*******************************************************************************
 *
 * Function create_thread
 *
 * Description creat a select loop
 *
 * Returns pthread_t
 *
 ******************************************************************************/
static inline pthread_t create_thread(void* (*start_routine)(void*),
                                      void* arg) {
  APPL_TRACE_DEBUG("create_thread: entered");
  pthread_attr_t thread_attr;

  pthread_attr_init(&thread_attr);
  pthread_attr_setdetachstate(&thread_attr, PTHREAD_CREATE_JOINABLE);
  pthread_t thread_id = -1;
  if (pthread_create(&thread_id, &thread_attr, start_routine, arg) != 0) {
    APPL_TRACE_ERROR("pthread_create : %s", strerror(errno));
    return -1;
  }
  APPL_TRACE_DEBUG("create_thread: thread created successfully");
  return thread_id;
}

/* Internal function to close the UHID driver*/
static void uhid_fd_close(btif_hh_device_t* p_dev) {
  if (p_dev->fd >= 0) {
    struct uhid_event ev = {};
    ev.type = UHID_DESTROY;
    uhid_write(p_dev->fd, &ev);
    LOG_DEBUG("Closing fd=%d, addr:%s", p_dev->fd,
              ADDRESS_TO_LOGGABLE_CSTR(p_dev->bd_addr));
    close(p_dev->fd);
    p_dev->fd = -1;
  }
}

/* Internal function to open the UHID driver*/
static bool uhid_fd_open(btif_hh_device_t* p_dev) {
  if (p_dev->fd < 0) {
    p_dev->fd = open(dev_path, O_RDWR | O_CLOEXEC);
    if (p_dev->fd < 0) {
      LOG_ERROR("Failed to open uhid, err:%s", strerror(errno));
      return false;
    }
  }

  if (p_dev->hh_keep_polling == 0) {
    p_dev->hh_keep_polling = 1;
    p_dev->hh_poll_thread_id = create_thread(btif_hh_poll_event_thread, p_dev);
  }
  return true;
}

/*******************************************************************************
 *
 * Function btif_hh_poll_event_thread
 *
 * Description the polling thread which polls for event from UHID driver
 *
 * Returns void
 *
 ******************************************************************************/
static void* btif_hh_poll_event_thread(void* arg) {
  btif_hh_device_t* p_dev = (btif_hh_device_t*)arg;
  struct pollfd pfds[1];
  pid_t pid = gettid();

  // This thread is created by bt_main_thread with RT priority. Lower the thread
  // priority here since the tasks in this thread is not timing critical.
  struct sched_param sched_params;
  sched_params.sched_priority = THREAD_NORMAL_PRIORITY;
  if (sched_setscheduler(pid, SCHED_OTHER, &sched_params)) {
    LOG_ERROR("Failed to set thread priority to normal: %s", strerror(errno));
    p_dev->hh_poll_thread_id = -1;
    p_dev->hh_keep_polling = 0;
    uhid_fd_close(p_dev);
    return 0;
  }

  pthread_setname_np(pthread_self(), BT_HH_THREAD);
  LOG_DEBUG("Host hid polling thread created name:%s pid:%d fd:%d",
            BT_HH_THREAD, pid, p_dev->fd);

  pfds[0].fd = p_dev->fd;
  pfds[0].events = POLLIN;

  // Set the uhid fd as non-blocking to ensure we never block the BTU thread
  uhid_set_non_blocking(p_dev->fd);

  while (p_dev->hh_keep_polling) {
    int ret;
    int counter = 0;

    do {
      if (counter++ > BTA_HH_UHID_INTERRUPT_COUNT_MAX) {
        LOG_ERROR("Polling interrupted");
        break;
      }
      ret = poll(pfds, 1, BTA_HH_UHID_POLL_PERIOD_MS);
    } while (ret == -1 && errno == EINTR);

    if (ret < 0) {
      LOG_ERROR("Cannot poll for fds: %s\n", strerror(errno));
      break;
    }
    if (pfds[0].revents & POLLIN) {
      APPL_TRACE_DEBUG("%s: POLLIN", __func__);
      ret = uhid_read_event(p_dev);
      if (ret != 0) {
        LOG_ERROR("Unhandled UHID event");
        break;
      }
    }
  }

  /* Todo: Disconnect if loop exited due to a failure */
  LOG_INFO("Polling thread stopped for device %s",
           ADDRESS_TO_LOGGABLE_CSTR(p_dev->bd_addr));
  p_dev->hh_poll_thread_id = -1;
  p_dev->hh_keep_polling = 0;
  uhid_fd_close(p_dev);
  return 0;
}

int bta_hh_co_write(int fd, uint8_t* rpt, uint16_t len) {
  APPL_TRACE_VERBOSE("%s: UHID write %d", __func__, len);

  struct uhid_event ev;
  memset(&ev, 0, sizeof(ev));
  ev.type = UHID_INPUT;
  ev.u.input.size = len;
  if (len > sizeof(ev.u.input.data)) {
    APPL_TRACE_WARNING("%s: Report size greater than allowed size", __func__);
    return -1;
  }
  memcpy(ev.u.input.data, rpt, len);

  return uhid_write(fd, &ev);
}

/*******************************************************************************
 *
 * Function      bta_hh_co_open
 *
 * Description   When connection is opened, this call-out function is executed
 *               by HH to do platform specific initialization.
 *
 * Returns       True if platform specific initialization is successful
 ******************************************************************************/
bool bta_hh_co_open(uint8_t dev_handle, uint8_t sub_class,
                    tBTA_HH_ATTR_MASK attr_mask, uint8_t app_id) {
  uint32_t i;
  btif_hh_device_t* p_dev = NULL;

  if (dev_handle == BTA_HH_INVALID_HANDLE) {
    LOG_WARN("dev_handle (%d) is invalid", dev_handle);
    return false;
  }

  for (i = 0; i < BTIF_HH_MAX_HID; i++) {
    p_dev = &btif_hh_cb.devices[i];
    if (p_dev->dev_status != BTHH_CONN_STATE_UNKNOWN &&
        p_dev->dev_handle == dev_handle) {
      // We found a device with the same handle. Must be a device reconnected.
      LOG_INFO(
          "Found an existing device with the same handle dev_status=%d, "
          "address=%s, attr_mask=0x%04x, sub_class=0x%02x, app_id=%d",
          p_dev->dev_status, ADDRESS_TO_LOGGABLE_CSTR(p_dev->bd_addr),
          p_dev->attr_mask, p_dev->sub_class, p_dev->app_id);

      if (!uhid_fd_open(p_dev)) {
        return false;
      }
      break;
    }
    p_dev = NULL;
  }

  if (p_dev == NULL) {
    // Did not find a device reconnection case. Find an empty slot now.
    for (i = 0; i < BTIF_HH_MAX_HID; i++) {
      if (btif_hh_cb.devices[i].dev_status == BTHH_CONN_STATE_UNKNOWN) {
        p_dev = &btif_hh_cb.devices[i];
        p_dev->fd = -1;
        p_dev->hh_keep_polling = 0;

        // This is a new device, open the uhid driver now.
        if (!uhid_fd_open(p_dev)) {
          return false;
        }

        p_dev->dev_handle = dev_handle;
        p_dev->attr_mask = attr_mask;
        p_dev->sub_class = sub_class;
        p_dev->app_id = app_id;
        p_dev->local_vup = false;

        btif_hh_cb.device_num++;
        break;
      }
    }
  }

  if (p_dev == NULL) {
    LOG_ERROR("Too many HID devices are connected");
    return false;
  }

  p_dev->dev_status = BTHH_CONN_STATE_CONNECTED;
  p_dev->get_rpt_id_queue = fixed_queue_new(SIZE_MAX);
  CHECK(p_dev->get_rpt_id_queue);
#if ENABLE_UHID_SET_REPORT
  p_dev->set_rpt_id_queue = fixed_queue_new(SIZE_MAX);
  CHECK(p_dev->set_rpt_id_queue);
#endif  // ENABLE_UHID_SET_REPORT

  LOG_DEBUG("Return device status %d", p_dev->dev_status);
  return true;
}

/*******************************************************************************
 *
 * Function      bta_hh_co_close
 *
 * Description   When connection is closed, this call-out function is executed
 *               by HH to do platform specific finalization.
 *
 * Parameters    p_dev  - device
 *
 * Returns       void.
 ******************************************************************************/
void bta_hh_co_close(btif_hh_device_t* p_dev) {
  LOG_INFO("Closing device handle=%d, status=%d, address=%s", p_dev->dev_handle,
           p_dev->dev_status, ADDRESS_TO_LOGGABLE_CSTR(p_dev->bd_addr));

  /* Clear the queues */
  fixed_queue_flush(p_dev->get_rpt_id_queue, osi_free);
  fixed_queue_free(p_dev->get_rpt_id_queue, NULL);
  p_dev->get_rpt_id_queue = NULL;
#if ENABLE_UHID_SET_REPORT
  fixed_queue_flush(p_dev->set_rpt_id_queue, osi_free);
  fixed_queue_free(p_dev->set_rpt_id_queue, nullptr);
  p_dev->set_rpt_id_queue = nullptr;
#endif  // ENABLE_UHID_SET_REPORT

  /* Stop the polling thread */
  if (p_dev->hh_keep_polling) {
    p_dev->hh_keep_polling = 0;
    pthread_join(p_dev->hh_poll_thread_id, NULL);
    p_dev->hh_poll_thread_id = -1;
  }
  /* UHID file descriptor is closed by the polling thread */
}

/*******************************************************************************
 *
 * Function         bta_hh_co_data
 *
 * Description      This function is executed by BTA when HID host receive a
 *                  data report.
 *
 * Parameters       dev_handle  - device handle
 *                  *p_rpt      - pointer to the report data
 *                  len         - length of report data
 *                  mode        - Hid host Protocol Mode
 *                  sub_clas    - Device Subclass
 *                  app_id      - application id
 *
 * Returns          void
 ******************************************************************************/
void bta_hh_co_data(uint8_t dev_handle, uint8_t* p_rpt, uint16_t len,
                    tBTA_HH_PROTO_MODE mode, uint8_t sub_class,
                    uint8_t ctry_code, UNUSED_ATTR const RawAddress& peer_addr,
                    uint8_t app_id) {
  btif_hh_device_t* p_dev;

  APPL_TRACE_DEBUG(
      "%s: dev_handle = %d, subclass = 0x%02X, mode = %d, "
      "ctry_code = %d, app_id = %d",
      __func__, dev_handle, sub_class, mode, ctry_code, app_id);

  p_dev = btif_hh_find_connected_dev_by_handle(dev_handle);
  if (p_dev == NULL) {
    APPL_TRACE_WARNING("%s: Error: unknown HID device handle %d", __func__,
                       dev_handle);
    return;
  }

  // Wait a maximum of MAX_POLLING_ATTEMPTS x POLLING_SLEEP_DURATION in case
  // device creation is pending.
  if (p_dev->fd >= 0) {
    uint32_t polling_attempts = 0;
    while (!p_dev->ready_for_data &&
           polling_attempts++ < BTIF_HH_MAX_POLLING_ATTEMPTS) {
      usleep(BTIF_HH_POLLING_SLEEP_DURATION_US);
    }
  }

  // Send the HID data to the kernel.
  if ((p_dev->fd >= 0) && p_dev->ready_for_data) {
    bta_hh_co_write(p_dev->fd, p_rpt, len);
  } else {
    APPL_TRACE_WARNING("%s: Error: fd = %d, ready %d, len = %d", __func__,
                       p_dev->fd, p_dev->ready_for_data, len);
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_co_send_hid_info
 *
 * Description      This function is called in btif_hh.c to process DSCP
 *                  received.
 *
 * Parameters       dev_handle  - device handle
 *                  dscp_len    - report descriptor length
 *                  *p_dscp     - report descriptor
 *
 * Returns          void
 ******************************************************************************/
void bta_hh_co_send_hid_info(btif_hh_device_t* p_dev, const char* dev_name,
                             uint16_t vendor_id, uint16_t product_id,
                             uint16_t version, uint8_t ctry_code, int dscp_len,
                             uint8_t* p_dscp) {
  int result;
  struct uhid_event ev;

  if (p_dev->fd < 0) {
    APPL_TRACE_WARNING("%s: Error: fd = %d, dscp_len = %d", __func__, p_dev->fd,
                       dscp_len);
    return;
  }

  APPL_TRACE_WARNING("%s: fd = %d, name = [%s], dscp_len = %d", __func__,
                     p_dev->fd, dev_name, dscp_len);
  APPL_TRACE_WARNING(
      "%s: vendor_id = 0x%04x, product_id = 0x%04x, version= 0x%04x,"
      "ctry_code=0x%02x",
      __func__, vendor_id, product_id, version, ctry_code);

  // Create and send hid descriptor to kernel
  memset(&ev, 0, sizeof(ev));
  ev.type = UHID_CREATE;
  strlcpy((char*)ev.u.create.name, dev_name, sizeof(ev.u.create.name));
  // TODO (b/258090765) fix: ToString -> ToColonSepHexString
  snprintf((char*)ev.u.create.uniq, sizeof(ev.u.create.uniq), "%s",
           p_dev->bd_addr.ToString().c_str());

  // Write controller address to phys field to correlate the hid device with a
  // specific bluetooth controller.
  const controller_t* controller = controller_get_interface();
  // TODO (b/258090765) fix: ToString -> ToColonSepHexString
  snprintf((char*)ev.u.create.phys, sizeof(ev.u.create.phys), "%s",
           controller->get_address()->ToString().c_str());

  ev.u.create.rd_size = dscp_len;
  ev.u.create.rd_data = p_dscp;
  ev.u.create.bus = BUS_BLUETOOTH;
  ev.u.create.vendor = vendor_id;
  ev.u.create.product = product_id;
  ev.u.create.version = version;
  ev.u.create.country = ctry_code;
  result = uhid_write(p_dev->fd, &ev);

  APPL_TRACE_WARNING(
      "%s: wrote descriptor to fd = %d, dscp_len = %d, result = %d", __func__,
      p_dev->fd, dscp_len, result);

  if (result) {
    APPL_TRACE_WARNING("%s: Error: failed to send DSCP, result = %d", __func__,
                       result);

    /* The HID report descriptor is corrupted. Close the driver. */
    close(p_dev->fd);
    p_dev->fd = -1;
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_co_set_rpt_rsp
 *
 * Description      This callout function is executed by HH when Set Report
 *                  Response is received on Control Channel.
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_hh_co_set_rpt_rsp(uint8_t dev_handle, uint8_t status) {
#if ENABLE_UHID_SET_REPORT
  LOG_VERBOSE("dev_handle = %d", dev_handle);

  btif_hh_device_t* p_dev = btif_hh_find_connected_dev_by_handle(dev_handle);
  if (p_dev == nullptr) {
    LOG_WARN("Unknown HID device handle %d", dev_handle);
    return;
  }

  if (!p_dev->set_rpt_id_queue) {
    LOG_WARN("Missing UHID_SET_REPORT id queue");
    return;
  }

  // Send the HID set report reply to the kernel.
  if (p_dev->fd < 0) {
    LOG_ERROR("Unexpected Set Report response");
    return;
  }

  uint32_t* context = (uint32_t*)fixed_queue_try_dequeue(p_dev->set_rpt_id_queue);

  if (context == nullptr) {
    LOG_WARN("No pending UHID_SET_REPORT");
    return;
  }

  struct uhid_event ev = {
      .type = UHID_SET_REPORT_REPLY,
      .u = {
          .set_report_reply = {
              .id = *context,
              .err = status,
          },
      },
  };
  uhid_write(p_dev->fd, &ev);
  osi_free(context);

#else
  LOG_ERROR("UHID_SET_REPORT_REPLY not supported");
#endif  // ENABLE_UHID_SET_REPORT
}

/*******************************************************************************
 *
 * Function         bta_hh_co_get_rpt_rsp
 *
 * Description      This callout function is executed by HH when Get Report
 *                  Response is received on Control Channel.
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_hh_co_get_rpt_rsp(uint8_t dev_handle, uint8_t status,
                           const uint8_t* p_rpt, uint16_t len) {
  btif_hh_device_t* p_dev;

  LOG_VERBOSE("dev_handle = %d, status = %d", dev_handle, status);

  p_dev = btif_hh_find_connected_dev_by_handle(dev_handle);
  if (p_dev == nullptr) {
    LOG_WARN("Unknown HID device handle %d", dev_handle);
    return;
  }

  if (!p_dev->get_rpt_id_queue) {
    LOG_WARN("Missing UHID_GET_REPORT id queue");
    return;
  }

  // Send the HID report to the kernel.
  if (p_dev->fd < 0) {
    LOG_WARN("Unexpected Get Report response");
    return;
  }

  uint32_t* context = (uint32_t*)fixed_queue_try_dequeue(p_dev->get_rpt_id_queue);

  if (context == nullptr) {
    LOG_WARN("No pending UHID_GET_REPORT");
    return;
  }

  if (len == 0 || len > UHID_DATA_MAX) {
    LOG_WARN("Invalid report size = %d", len);
    return;
  }

  struct uhid_event ev = {
      .type = UHID_FEATURE_ANSWER,
      .u = {
          .feature_answer = {
              .id = *context,
              .err = status,
              .size = len,
          },
      },
  };
  memcpy(ev.u.feature_answer.data, p_rpt, len);

  uhid_write(p_dev->fd, &ev);
  osi_free(context);
}

/*******************************************************************************
 *
 * Function         bta_hh_le_co_rpt_info
 *
 * Description      This callout function is to convey the report information on
 *                  a HOGP device to the application. Application can save this
 *                  information in NV if device is bonded and load it back when
 *                  stack reboot.
 *
 * Parameters       remote_bda  - remote device address
 *                  p_entry     - report entry pointer
 *                  app_id      - application id
 *
 * Returns          void.
 *
 ******************************************************************************/
void bta_hh_le_co_rpt_info(const RawAddress& remote_bda,
                           tBTA_HH_RPT_CACHE_ENTRY* p_entry,
                           UNUSED_ATTR uint8_t app_id) {
  unsigned idx = 0;

  std::string addrstr = remote_bda.ToString();
  const char* bdstr = addrstr.c_str();

  size_t len = btif_config_get_bin_length(bdstr, "HidReport");
  if (len >= sizeof(tBTA_HH_RPT_CACHE_ENTRY) && len <= sizeof(sReportCache)) {
    btif_config_get_bin(bdstr, "HidReport", (uint8_t*)sReportCache, &len);
    idx = len / sizeof(tBTA_HH_RPT_CACHE_ENTRY);
  }

  if (idx < BTA_HH_NV_LOAD_MAX) {
    memcpy(&sReportCache[idx++], p_entry, sizeof(tBTA_HH_RPT_CACHE_ENTRY));
    btif_config_set_bin(bdstr, "HidReport", (const uint8_t*)sReportCache,
                        idx * sizeof(tBTA_HH_RPT_CACHE_ENTRY));
    btif_config_set_int(bdstr, "HidReportVersion", BTA_HH_CACHE_REPORT_VERSION);
    BTIF_TRACE_DEBUG("%s() - Saving report; dev=%s, idx=%d", __func__,
                     ADDRESS_TO_LOGGABLE_CSTR(remote_bda), idx);
  }
}

/*******************************************************************************
 *
 * Function         bta_hh_le_co_cache_load
 *
 * Description      This callout function is to request the application to load
 *                  the cached HOGP report if there is any. When cache reading
 *                  is completed, bta_hh_le_co_cache_load() is called by the
 *                  application.
 *
 * Parameters       remote_bda  - remote device address
 *                  p_num_rpt   - number of cached report
 *                  app_id      - application id
 *
 * Returns          the cached report array
 *
 ******************************************************************************/
tBTA_HH_RPT_CACHE_ENTRY* bta_hh_le_co_cache_load(const RawAddress& remote_bda,
                                                 uint8_t* p_num_rpt,
                                                 UNUSED_ATTR uint8_t app_id) {
  std::string addrstr = remote_bda.ToString();
  const char* bdstr = addrstr.c_str();

  size_t len = btif_config_get_bin_length(bdstr, "HidReport");
  if (!p_num_rpt || len < sizeof(tBTA_HH_RPT_CACHE_ENTRY)) return NULL;

  if (len > sizeof(sReportCache)) len = sizeof(sReportCache);
  btif_config_get_bin(bdstr, "HidReport", (uint8_t*)sReportCache, &len);

  int cache_version = -1;
  btif_config_get_int(bdstr, "HidReportVersion", &cache_version);

  if (cache_version != BTA_HH_CACHE_REPORT_VERSION) {
    bta_hh_le_co_reset_rpt_cache(remote_bda, app_id);
    return NULL;
  }

  *p_num_rpt = len / sizeof(tBTA_HH_RPT_CACHE_ENTRY);

  BTIF_TRACE_DEBUG("%s() - Loaded %d reports; dev=%s", __func__, *p_num_rpt,
                   ADDRESS_TO_LOGGABLE_CSTR(remote_bda));

  return sReportCache;
}

/*******************************************************************************
 *
 * Function         bta_hh_le_co_reset_rpt_cache
 *
 * Description      This callout function is to reset the HOGP device cache.
 *
 * Parameters       remote_bda  - remote device address
 *
 * Returns          none
 *
 ******************************************************************************/
void bta_hh_le_co_reset_rpt_cache(const RawAddress& remote_bda,
                                  UNUSED_ATTR uint8_t app_id) {
  std::string addrstr = remote_bda.ToString();
  const char* bdstr = addrstr.c_str();

  btif_config_remove(bdstr, "HidReport");
  btif_config_remove(bdstr, "HidReportVersion");
  BTIF_TRACE_DEBUG("%s() - Reset cache for bda %s", __func__,
                   ADDRESS_TO_LOGGABLE_CSTR(remote_bda));
}
