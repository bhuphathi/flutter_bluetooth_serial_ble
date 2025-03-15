1. Connect with a bluetooth device, e.g. a bluetooth sensor device
2. Request continuous sensor data
3. Sensor device sends real time data continuously
4. After few seconds, Android sends a sniff mode request
5. Now sensor device data is received only every ~500ms in big chunks.

startKeepAliveThread method added to prevent above cause.