pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
rootProject.name = "File-Utilities"
include(":host")
include(":All_Media_Studio_GitHub_Project_app")
project(":All_Media_Studio_GitHub_Project_app").projectDir = file("modules/All_Media_Studio_GitHub_Project_app")
include(":FileExplorer_app")
project(":FileExplorer_app").projectDir = file("modules/FileExplorer_app")
include(":FileManagerPro_app")
project(":FileManagerPro_app").projectDir = file("modules/FileManagerPro_app")
include(":ImageStudioAndroid_app")
project(":ImageStudioAndroid_app").projectDir = file("modules/ImageStudioAndroid_app")
include(":YTSaver_app")
project(":YTSaver_app").projectDir = file("modules/YTSaver_app")
include(":WebGrab_app")
project(":WebGrab_app").projectDir = file("modules/WebGrab_app")
include(":VoiceKit")
project(":VoiceKit").projectDir = file("modules/VoiceKit")
