import org.gradle.api.tasks.PathSensitivity.NONE
import java.nio.file.Files

plugins {
    `java-library`
    id("biz.aQute.bnd.builder")
    id("com.diffplug.spotless")
    id("publishing-conventions")
}

base {
    archivesName = "open-test-reporting-${project.name}"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
    withJavadocJar()
}

spotless {
    val spotlessDir = rootProject.file("gradle/spotless")
    val licenseHeaderFile = File(spotlessDir, "apache-license-2.0.java")
    val javaFormatterConfigFile = File(spotlessDir, "eclipse-formatter-settings.xml")

    java {
        licenseHeaderFile(licenseHeaderFile, "(package|import|open|module) ")
        val fullVersion = requiredVersionFromLibs("eclipse")
        val majorMinorVersion = "([0-9]+\\.[0-9]+).*".toRegex().matchEntire(fullVersion)!!.let { it.groups[1]!!.value }
        eclipse(majorMinorVersion).configFile(javaFormatterConfigFile)
        trimTrailingWhitespace()
        endWithNewline()
    }
}

fun Project.requiredVersionFromLibs(name: String) =
    libsVersionCatalog.findVersion(name).get().requiredVersion

fun Project.dependencyFromLibs(name: String) =
    libsVersionCatalog.findLibrary(name).get()

private val Project.libsVersionCatalog: VersionCatalog
    get() = the<VersionCatalogsExtension>().named("libs")

val cli by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isVisible = false
}

dependencies {
    cli(project(":cli"))
    cli(dependencyFromLibs("junit-platform-reporting"))
}

configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("${group}:open-test-reporting-tooling-spi"))
            .using(project(":tooling-spi"))
    }
}

tasks {
    compileJava {
        options.release.convention(8)
    }
    compileTestJava {
        options.release.convention(17)
    }
    jar {
        manifest {
            val moduleName = "org.opentest4j.reporting.${project.name.replace('-', '.')}"
            attributes(
                "Automatic-Module-Name" to moduleName,
                "Bundle-Name" to project.name,
                "Bundle-Description" to project.name,
                "Bundle-DocURL" to "https://github.com/ota4j-team/open-test-reporting",
                "Bundle-Vendor" to "opentest4j.org",
                "-exportcontents" to "org.opentest4j.reporting.*",
                "Bundle-SymbolicName" to moduleName,
            )
        }
    }
    javadoc {
        (options as StandardJavadocDocletOptions).apply {
            addBooleanOption("Werror", true)
            addBooleanOption("Xdoclint:all", true)
        }
    }
    tasks.withType<Jar>().configureEach {
        from(rootDir) {
            include("LICENSE.md")
            into("META-INF")
        }
    }

    val eventXmlFiles =
        files(test.map { it.reports.junitXml.outputLocation.get().asFileTree.matching { include("junit-platform-events-*.xml") } })

    val convertTestResultXmlToHierarchicalFormat by registering(JavaExec::class) {
        mustRunAfter(test)
        mainClass.set("org.opentest4j.reporting.cli.ReportingCli")
        args("convert")
        classpath(cli)
        inputs.files(eventXmlFiles).withPathSensitivity(NONE).skipWhenEmpty()
        argumentProviders += CommandLineArgumentProvider {
            listOf(eventXmlFiles.singleFile.absolutePath)
        }
    }

    val generateHtmlReport by registering(JavaExec::class) {
        mustRunAfter(test)
        mainClass.set("org.opentest4j.reporting.cli.ReportingCli")
        args("html-report")
        classpath(cli)
        inputs.files(eventXmlFiles).withPathSensitivity(NONE).skipWhenEmpty()
        argumentProviders += CommandLineArgumentProvider {
            listOf(eventXmlFiles.singleFile.absolutePath)
        }
    }

    withType<Test>().configureEach {
        useJUnitPlatform()
    }

    test {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Djunit.platform.reporting.open.xml.enabled=true",
                "-Djunit.platform.reporting.output.dir=${reports.junitXml.outputLocation.get().asFile.absolutePath}"
            )
        }

        doFirst {
            files(reports.junitXml.outputLocation.get().asFileTree.matching {
                include("junit-platform-events-*.xml")
                include("junit-platform-events-*.html")
                include("hierarchy.xml")
            }).files.forEach {
                Files.delete(it.toPath())
            }
        }

        finalizedBy(convertTestResultXmlToHierarchicalFormat, generateHtmlReport)
    }
}

configure<PublishingExtension> {
    publications {
        named<MavenPublication>("maven") {
            from(components["java"])
            artifactId = base.archivesName.get()
        }
    }
}
