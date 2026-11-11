# Keep reflection access to BluetoothGatt.refresh() for Android GATT cache invalidation
-keepclassmembers class android.bluetooth.BluetoothGatt {
    public boolean refresh();
}
