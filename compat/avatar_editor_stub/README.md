# com.pvr.avatareditor stub

The PICO user center's avatar preview SDK does this while loading a profile:

```java
context.getPackageManager().getPackageInfo("com.pvr.avatareditor", 0)
```

On Horizon OS that package does not exist, so the call throws, the SDK logs
`getCharacterData exception`, and the avatar section never appears.

This stub satisfies the lookup. It also declares `com.pvr.avatareditor.MainActivity`
(the component the user center launches for "进入形象中心") with a short notice,
because the real editor is a large PICO OS app that needs PICO private services.

Build:

```
javac --release 8 -classpath <android.jar> -d classes src/com/pvr/avatareditor/MainActivity.java
d8 --lib <android.jar> --min-api 29 --output dex classes/**/*.class
aapt2 link -o base.apk -I <android.jar> --manifest AndroidManifest.xml
# add dex/classes.dex into base.apk, zipalign, apksigner sign
```