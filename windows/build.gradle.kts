import java.net.URI
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.12.0-alpha02"
}

group = "com.selxo.rougo"
version = "2.7.7-windows"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.json:json:20250517")
    implementation("uk.co.caprica:vlcj:4.11.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    val javafxVersion = "21.0.8"
    implementation("org.openjfx:javafx-base:$javafxVersion:win")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:win")
    implementation("org.openjfx:javafx-controls:$javafxVersion:win")
    implementation("org.openjfx:javafx-swing:$javafxVersion:win")
    implementation("org.openjfx:javafx-media:$javafxVersion:win")
    implementation("org.openjfx:javafx-web:$javafxVersion:win")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

val prepareBundledTools by tasks.registering {
    val resourcesRoot = layout.buildDirectory.dir("appResources")
    val toolsDir = resourcesRoot.map { it.dir("windows/tools") }
    val downloadsDir = layout.buildDirectory.dir("toolDownloads")
    outputs.dir(toolsDir)

    fun download(url: String, target: File) {
        if (target.isFile && target.length() > 1024L) return
        target.parentFile.mkdirs()
        var current = URI(url).toURL()
        repeat(8) {
            val connection = current.openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Rougo-Windows-Build")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: error("Redirect without Location for $current")
                current = URI(location).toURL()
                return@repeat
            }
            if (code !in 200..299) error("Download failed for $current: HTTP $code")
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() <= 1024L) {
                target.delete()
                error("Download for $url produced an unexpectedly small file")
            }
            return
        }
        error("Too many redirects for $url")
    }

    fun unzipSelected(zip: File, destination: File, include: (String) -> Boolean, mapName: (String) -> String) {
        ZipFile(zip).use { archive ->
            archive.entries().asSequence().forEach { entry ->
                if (entry.isDirectory || !include(entry.name)) return@forEach
                val output = destination.resolve(mapName(entry.name))
                output.parentFile.mkdirs()
                archive.getInputStream(entry).use { input ->
                    output.outputStream().use { out -> input.copyTo(out) }
                }
            }
        }
    }

    doLast {
        val out = toolsDir.get().asFile
        val downloads = downloadsDir.get().asFile
        out.mkdirs()

        download(
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe",
            out.resolve("yt-dlp.exe")
        )

        download(
            "https://raw.githubusercontent.com/richardpl/arnndn-models/master/std.rnnn",
            out.resolve("rnnoise/std.rnnn")
        )

        val ffmpegZip = downloads.resolve("ffmpeg-release-essentials.zip")
        download(
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
            ffmpegZip
        )
        unzipSelected(
            ffmpegZip,
            out,
            include = { it.endsWith("/bin/ffmpeg.exe") || it.endsWith("/bin/ffprobe.exe") },
            mapName = { "ffmpeg/${it.substringAfterLast('/')}" }
        )

        val vlcVersion = "3.0.23"
        val vlcZip = downloads.resolve("vlc-$vlcVersion-win64.zip")
        download(
            "https://get.videolan.org/vlc/$vlcVersion/win64/vlc-$vlcVersion-win64.zip",
            vlcZip
        )
        out.resolve("vlc").deleteRecursively()
        unzipSelected(
            vlcZip,
            out.resolve("vlc"),
            include = {
                val relative = it.substringAfter('/')
                relative.startsWith("plugins/") ||
                    relative.startsWith("locale/") ||
                    relative.startsWith("hrtfs/") ||
                    relative in setOf("libvlc.dll", "libvlccore.dll", "COPYING.txt", "AUTHORS.txt", "README.txt")
            },
            mapName = { it.substringAfter('/') }
        )
    }
}

tasks.matching {
    it.name == "run" ||
        it.name.startsWith("package") ||
        it.name.startsWith("create") ||
        it.name.startsWith("prepareAppResources")
}.configureEach {
    dependsOn(prepareBundledTools)
}

compose.desktop {
    application {
        mainClass = "com.selxo.rougo.windows.MainKt"

        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi
            )
            packageName = "Rougo"
            packageVersion = "2.7.7"
            description = "Windows port of Rougo language shadowing player"
            copyright = "GPL-3.0"
            vendor = "Rougo"
            windows {
                iconFile.set(project.file("src/main/resources/icons/rougo.ico"))
            }
        }
    }
}
