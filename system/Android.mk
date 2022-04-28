LOCAL_PATH := $(call my-dir)

#LINT.IfChange
LOCAL_bluetooth_project_dir := $(LOCAL_PATH)

LOCAL_cert_test_sources := \
    $(call all-named-files-under,*.py,blueberry) \
    $(call all-named-files-under,*.yaml,blueberry) \
  	setup.py
LOCAL_cert_test_sources := \
	$(filter-out gd_cert_venv% venv%, $(LOCAL_cert_test_sources))
LOCAL_cert_test_sources := \
	$(addprefix $(LOCAL_PATH)/, $(LOCAL_cert_test_sources))

LOCAL_host_executables := \
	$(HOST_OUT_EXECUTABLES)/bluetooth_stack_with_facade \
	$(HOST_OUT_EXECUTABLES)/bluetooth_with_facades \
	$(HOST_OUT_EXECUTABLES)/bt_topshim_facade \
	$(HOST_OUT_EXECUTABLES)/root-canal

LOCAL_host_python_extension_libraries := \
	$(HOST_OUT_SHARED_LIBRARIES)/bluetooth_packets_python3.so

LOCAL_host_libraries := \
	$(HOST_OUT_SHARED_LIBRARIES)/libbase.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libbluetooth.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libbluetooth_gd.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libc++.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libchrome.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libcrypto-host.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libcutils.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libevent-host.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libflatbuffers-cpp.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libgrpc++_unsecure.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libgrpc++.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libgrpc_wrap.so \
	$(HOST_OUT_SHARED_LIBRARIES)/liblog.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libssl-host.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libz-host.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libprotobuf-cpp-full.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libunwindstack.so \
	$(HOST_OUT_SHARED_LIBRARIES)/liblzma.so \
	$(HOST_OUT_SHARED_LIBRARIES)/libbacktrace.so

LOCAL_target_executables := \
	$(TARGET_OUT_EXECUTABLES)/bluetooth_stack_with_facade

LOCAL_target_libraries := \
	$(TARGET_OUT_SHARED_LIBRARIES)/android.system.suspend.control-V1-ndk.so \
	$(TARGET_OUT_SHARED_LIBRARIES)/libcrypto.so \
	$(TARGET_OUT_SHARED_LIBRARIES)/libbluetooth_gd.so \
	$(TARGET_OUT_SHARED_LIBRARIES)/libgrpc++_unsecure.so \
	$(TARGET_OUT_SHARED_LIBRARIES)/libgrpc++.so \
	$(TARGET_OUT_SHARED_LIBRARIES)/libgrpc_wrap.so \
	$(TARGET_OUT_SHARED_LIBRARIES)/libstatslog_bt.so
#LINT.ThenChange(gd/cert/run)

bluetooth_cert_src_and_bin_zip := \
	$(call intermediates-dir-for,PACKAGING,bluetooth_cert_src_and_bin,HOST)/bluetooth_cert_src_and_bin.zip

# Assume 64-bit OS
$(bluetooth_cert_src_and_bin_zip): PRIVATE_bluetooth_project_dir := $(LOCAL_bluetooth_project_dir)
$(bluetooth_cert_src_and_bin_zip): PRIVATE_cert_test_sources := $(LOCAL_cert_test_sources)
$(bluetooth_cert_src_and_bin_zip): PRIVATE_host_executables := $(LOCAL_host_executables)
$(bluetooth_cert_src_and_bin_zip): PRIVATE_host_python_extension_libraries := $(LOCAL_host_python_extension_libraries)
$(bluetooth_cert_src_and_bin_zip): PRIVATE_host_libraries := $(LOCAL_host_libraries)
$(bluetooth_cert_src_and_bin_zip): PRIVATE_target_executables := $(LOCAL_target_executables)
$(bluetooth_cert_src_and_bin_zip): PRIVATE_target_libraries := $(LOCAL_target_libraries)
$(bluetooth_cert_src_and_bin_zip): $(SOONG_ZIP) $(LOCAL_cert_test_sources) \
		$(LOCAL_host_executables) $(LOCAL_host_libraries) $(LOCAL_host_python_extension_libraries) \
		$(LOCAL_target_executables) $(LOCAL_target_libraries)
	$(hide) $(SOONG_ZIP) -d -o $@ \
		-C $(PRIVATE_bluetooth_project_dir) $(addprefix -f ,$(PRIVATE_cert_test_sources)) \
		-C $(HOST_OUT_EXECUTABLES) $(addprefix -f ,$(PRIVATE_host_executables)) \
		-C $(HOST_OUT_SHARED_LIBRARIES) $(addprefix -f ,$(PRIVATE_host_python_extension_libraries)) \
		-P lib64 \
		-C $(HOST_OUT_SHARED_LIBRARIES) $(addprefix -f ,$(PRIVATE_host_libraries)) \
		-P target \
		-C $(TARGET_OUT_EXECUTABLES) $(addprefix -f ,$(PRIVATE_target_executables)) \
		-C $(TARGET_OUT_SHARED_LIBRARIES) $(addprefix -f ,$(PRIVATE_target_libraries))

$(call declare-container-license-metadata,$(bluetooth_cert_src_and_bin_zip),\
    SPDX-license-identifier-Apache-2.0 SPDX-license-identifier-BSD SPDX-license-identifier-MIT legacy_unencumbered,\
    notice, $(LOCAL_PATH)/../NOTICE,,packages/modules/Bluetooth)
$(call declare-container-license-deps,$(bluetooth_cert_src_and_bin_zip),\
    $(LOCAL_host_python_extension_libraries) $(LOCAL_target_executables) $(LOCAL_target_libraries),$(bluetooth_cert_src_and_bin_zip):)

# TODO: Find a better way to locate output from SOONG genrule()
LOCAL_facade_generated_py_zip := \
	$(SOONG_OUT_DIR)/.intermediates/packages/modules/Bluetooth/system/BlueberryFacadeAndCertGeneratedStub_py/gen/blueberry_facade_generated_py.zip

bluetooth_cert_tests_py_package_zip := \
	$(call intermediates-dir-for,PACKAGING,bluetooth_cert_tests_py_package,HOST)/bluetooth_cert_tests.zip

$(bluetooth_cert_tests_py_package_zip): PRIVATE_bluetooth_project_dir := $(LOCAL_bluetooth_project_dir)
$(bluetooth_cert_tests_py_package_zip): PRIVATE_cert_src_and_bin_zip := $(bluetooth_cert_src_and_bin_zip)
$(bluetooth_cert_tests_py_package_zip): PRIVATE_facade_generated_py_zip := $(LOCAL_facade_generated_py_zip)
$(bluetooth_cert_tests_py_package_zip): $(SOONG_ZIP) \
		$(bluetooth_cert_src_and_bin_zip) $(bluetooth_cert_generated_py_zip)
	@echo "Packaging Bluetooth Cert Tests into $@"
	@rm -rf $(dir $@)bluetooth_cert_tests
	@mkdir -p $(dir $@)bluetooth_cert_tests
	$(hide) unzip -o -q $(PRIVATE_cert_src_and_bin_zip) -d $(dir $@)bluetooth_cert_tests
	$(hide) unzip -o -q $(PRIVATE_facade_generated_py_zip) -d $(dir $@)bluetooth_cert_tests
	# Make all subdirectory Python packages except lib64 and target
	$(hide) for f in `find $(dir $@)bluetooth_cert_tests -type d -name "*" \
					-not -path "$(dir $@)bluetooth_cert_tests/target*" \
					-not -path "$(dir $@)bluetooth_cert_tests/lib64*"` \
			; do (touch -a $$f/__init__.py) ; done
	$(hide) $(SOONG_ZIP) -d -o $@ -C $(dir $@)bluetooth_cert_tests -D $(dir $@)bluetooth_cert_tests \
		-P llvm_binutils -C $(LLVM_PREBUILTS_BASE)/linux-x86/$(LLVM_PREBUILTS_VERSION) \
		-f $(LLVM_PREBUILTS_BASE)/linux-x86/$(LLVM_PREBUILTS_VERSION)/bin/llvm-cov \
		-f $(LLVM_PREBUILTS_BASE)/linux-x86/$(LLVM_PREBUILTS_VERSION)/bin/llvm-profdata \
		-f $(LLVM_PREBUILTS_BASE)/linux-x86/$(LLVM_PREBUILTS_VERSION)/bin/llvm-symbolizer \
		-f $(LLVM_PREBUILTS_BASE)/linux-x86/$(LLVM_PREBUILTS_VERSION)/lib64/libc++.so.1

$(call declare-container-license-metadata,$(bluetooth_cert_tests_py_package_zip),\
    SPDX-license-identifier-Apache-2.0 SPDX-license-identifier-BSD SPDX-license-identifier-MIT legacy_unencumbered,\
    notice, $(LOCAL_PATH)/../NOTICE,,packages/modules/Bluetooth)
$(call declare-container-license-deps,$(bluetooth_cert_tests_py_package_zip),\
    $(bluetooth_cert_src_and_bin_zip) $(bluetooth_cert_generated_py_zip),$(bluetooth_cert_tests_py_package_zip):)

$(call dist-for-goals,bluetooth_stack_with_facade,$(bluetooth_cert_tests_py_package_zip):bluetooth_cert_tests.zip)
