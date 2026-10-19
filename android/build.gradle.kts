plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.wrapper {
    gradleVersion = "8.11.1"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"
}
