package com.sf12.rr3tool;

import android.os.ParcelFileDescriptor;

interface IRr3StorageService {
    int getUid();
    String findRr3Depot();
    ParcelFileDescriptor listFiles(String depotPath);
    ParcelFileDescriptor openFile(String depotPath, String filePath);
    void destroy() = 16777114;
}
