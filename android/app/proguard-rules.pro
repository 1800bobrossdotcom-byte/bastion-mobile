# Keep VpnService entry points
-keep class cam.bastion.mobile.vpn.** { *; }
# Room generated
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
