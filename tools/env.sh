# Source this before running ./gradlew in the sandbox: the home directory is read-only,
# so the SDK and Gradle caches live in the workspace's .toolchain directory.
T=/projects/sandbox/.toolchain
export GRADLE_USER_HOME=$T/gradle ANDROID_HOME=$T/android-sdk ANDROID_USER_HOME=$T/.android
J=$(mise where java@17)
export JAVA_HOME=$J PATH=$J/bin:$PATH
