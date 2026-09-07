plugins {
    id("niem.java-conventions")
}

description = "Content metadata and version compatibility (spec §7). What a domain module declares about itself."

dependencies {
    api(project(":core:canonical"))
    implementation(libs.snakeyaml)
}
