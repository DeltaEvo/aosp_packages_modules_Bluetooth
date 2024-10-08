#include "osi/semaphore.h"

#include <gtest/gtest.h>
#include <sys/select.h>
#include <unistd.h>

#include "common/message_loop_thread.h"
#include "osi/include/osi.h"
#include "osi/include/reactor.h"

using bluetooth::common::MessageLoopThread;

struct SemaphoreTestSequenceHelper {
  semaphore_t* semaphore;
  int counter;
};

namespace {
void sleep_then_increment_counter(void* context) {
  SemaphoreTestSequenceHelper* helper = reinterpret_cast<SemaphoreTestSequenceHelper*>(context);
  EXPECT_NE(helper, nullptr);
  if (helper == nullptr) {
    return;
  }

  EXPECT_NE(helper->semaphore, nullptr);
  if (helper->semaphore == nullptr) {
    return;
  }

  sleep(1);
  ++helper->counter;
  semaphore_post(helper->semaphore);
}
}  // namespace

class SemaphoreTest : public ::testing::Test {};

TEST_F(SemaphoreTest, test_new_simple) {
  semaphore_t* semaphore = semaphore_new(0);
  ASSERT_TRUE(semaphore != NULL);
  semaphore_free(semaphore);
}

TEST_F(SemaphoreTest, test_new_with_value) {
  semaphore_t* semaphore = semaphore_new(3);
  ASSERT_TRUE(semaphore != NULL);

  EXPECT_TRUE(semaphore_try_wait(semaphore));
  EXPECT_TRUE(semaphore_try_wait(semaphore));
  EXPECT_TRUE(semaphore_try_wait(semaphore));
  EXPECT_FALSE(semaphore_try_wait(semaphore));

  semaphore_free(semaphore);
}

TEST_F(SemaphoreTest, test_try_wait) {
  semaphore_t* semaphore = semaphore_new(0);
  ASSERT_TRUE(semaphore != NULL);

  EXPECT_FALSE(semaphore_try_wait(semaphore));
  semaphore_post(semaphore);
  EXPECT_TRUE(semaphore_try_wait(semaphore));
  EXPECT_FALSE(semaphore_try_wait(semaphore));

  semaphore_free(semaphore);
}

TEST_F(SemaphoreTest, test_wait_after_post) {
  semaphore_t* semaphore = semaphore_new(0);
  ASSERT_TRUE(semaphore != NULL);
  semaphore_post(semaphore);
  semaphore_wait(semaphore);
  semaphore_free(semaphore);
}

TEST_F(SemaphoreTest, test_ensure_wait) {
  semaphore_t* semaphore = semaphore_new(0);
  ASSERT_TRUE(semaphore != NULL);
  MessageLoopThread thread("semaphore_test_thread");
  thread.StartUp();
  ASSERT_TRUE(thread.IsRunning());

  EXPECT_FALSE(semaphore_try_wait(semaphore));
  SemaphoreTestSequenceHelper sequence_helper = {semaphore, 0};
  thread.DoInThread(FROM_HERE, base::BindOnce(sleep_then_increment_counter, &sequence_helper));
  semaphore_wait(semaphore);
  EXPECT_EQ(sequence_helper.counter, 1) << "semaphore_wait() did not wait for counter to increment";

  semaphore_free(semaphore);
  thread.ShutDown();
}
