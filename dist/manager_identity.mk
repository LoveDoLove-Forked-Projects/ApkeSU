# Keep this package and certificate hash in sync with the release Manager APK.
KSU_MANAGER_PACKAGE := io.github.fixz.apkesu
KSU_EXPECTED_SIZE := 0x02e8
KSU_EXPECTED_HASH := 1c89980c03432844cfe195dab90bfaecbcd987d19309da648014164be78007d1

# ABK Control manager identity. Both package name and certificate digest are
# checked before this manager receives an independent registry slot.
ABK_MANAGER_PACKAGE := com.abk.kernel
ABK_MANAGER_CERT_SIZE := 1407
ABK_MANAGER_CERT_SHA256 := 34e5e843952277759603cd0f949770b24c868530d80d7baeff08776a7e132b16
ABK_MANAGER_CERT_MAX_LENGTH := 2048
