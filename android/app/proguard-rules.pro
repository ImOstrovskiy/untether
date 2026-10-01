# Shizuku starts the user service in its own process by class name, with a Context.
-keep class io.github.imostrovskiy.untether.HotspotShell { public <init>(android.content.Context); }
# Keep line numbers for readable crash logs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
