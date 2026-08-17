#!/bin/bash
sed -i 's/advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)/advertiser.startAdvertising(settings, data, advertiseCallback)/g' PqcMeshChat/android/app/src/main/java/com/pqcmeshchat/BLEMeshModule.kt
