/*
 * From the repository root (NDK 28, JDK 21):
 * mkdir -p unidbg-android/target/native-pread64
 * NDK_BIN="$HOME/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
 * "$NDK_BIN/aarch64-linux-android23-clang" -std=c11 -Wall -Wextra -Werror -fPIC -shared -Wl,-soname,libnative-pread64.so unidbg-android/src/test/native/android/native-pread64.c -o unidbg-android/target/native-pread64/libnative-pread64-arm64.so
 * "$NDK_BIN/armv7a-linux-androideabi23-clang" -std=c11 -Wall -Wextra -Werror -fPIC -shared -Wl,-soname,libnative-pread64.so unidbg-android/src/test/native/android/native-pread64.c -o unidbg-android/target/native-pread64/libnative-pread64-arm32.so
 * JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 "$HOME/opt/apache-maven-3.9.9/bin/mvn" -o -pl unidbg-android -am -Dmaven.test.skip=false -Dtest=NativePread64IntegrationTest -DfailIfNoTests=false -Dunidbg.nativePread64Library64="$PWD/unidbg-android/target/native-pread64/libnative-pread64-arm64.so" -Dunidbg.nativePread64Library32="$PWD/unidbg-android/target/native-pread64/libnative-pread64-arm32.so" test
 */
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <unistd.h>

int pread64_results[20];
unsigned char pread64_bytes[11];

int pread64_probe(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    int write_only = open(path, O_WRONLY);
    if (write_only < 0) {
        close(fd);
        return -1;
    }

    pread64_results[0] = read(fd, pread64_bytes, 2);
    pread64_results[1] = pread64(fd, pread64_bytes + 2, 3, 4);
    pread64_results[2] = lseek(fd, 0, SEEK_CUR);
    pread64_results[3] = read(fd, pread64_bytes + 5, 2);
    pread64_results[4] = pread64(fd, pread64_bytes + 7, 2, INT64_C(0x80000003));
    pread64_results[5] = pread64(fd, pread64_bytes + 9, 2, INT64_C(0x100000005));
    pread64_results[6] = lseek(fd, 0, SEEK_CUR);
    pread64_results[7] = pread64(fd, pread64_bytes, 1, INT64_C(0x100000007));
    pread64_results[8] = pread64(fd, pread64_bytes, 0, 0);

    errno = 0;
    pread64_results[9] = pread64(fd, pread64_bytes, 1, -1);
    pread64_results[10] = errno;
    errno = 0;
    pread64_results[11] = pread64(-1, pread64_bytes, 1, 0);
    pread64_results[12] = errno;
    errno = 0;
    pread64_results[13] = pread64(write_only, pread64_bytes, 1, 0);
    pread64_results[14] = errno;
    errno = 0;
    pread64_results[15] = syscall(0x7fffffffL, 0x12345678L);
    pread64_results[16] = errno;
    pread64_results[17] = lseek(fd, 0, SEEK_CUR);
    pread64_results[18] = close(write_only);
    pread64_results[19] = close(fd);
    return 0;
}

int pread64_count_results[3];
unsigned char pread64_count_bytes[131089];

int pread64_count_probe(const char *path, size_t count) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    pread64_count_results[0] = pread64(fd, pread64_count_bytes, count, 0);
    pread64_count_results[1] = lseek(fd, 0, SEEK_CUR);
    pread64_count_results[2] = close(fd);
    return 0;
}

int pread64_invalid_count_results[11];
unsigned char pread64_invalid_count_bytes[8];

int pread64_invalid_count_probe(const char *path, size_t count) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    int write_only = open(path, O_WRONLY);
    if (write_only < 0) {
        close(fd);
        return -1;
    }
    for (size_t i = 0; i < sizeof pread64_invalid_count_bytes; ++i)
        pread64_invalid_count_bytes[i] = 0xa5;
    const int fds[] = {fd, fd, -1, write_only};
    const off64_t offsets[] = {0, -1, 0, 0};
    for (size_t i = 0; i < sizeof fds / sizeof fds[0]; ++i) {
        errno = 0;
        pread64_invalid_count_results[2 * i] = pread64(fds[i], pread64_invalid_count_bytes, count, offsets[i]);
        pread64_invalid_count_results[2 * i + 1] = errno;
    }
    pread64_invalid_count_results[8] = lseek(fd, 0, SEEK_CUR);
    pread64_invalid_count_results[9] = close(write_only);
    pread64_invalid_count_results[10] = close(fd);
    return 0;
}
