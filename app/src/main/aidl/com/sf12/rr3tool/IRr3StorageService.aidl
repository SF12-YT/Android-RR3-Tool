package com.sf12.rr3tool;

import android.os.ParcelFileDescriptor;

interface IRr3StorageService {
    int getUid() = 1;
    String findRr3Depot() = 2;
    ParcelFileDescriptor listFiles(String depotPath) = 3;
    ParcelFileDescriptor openFile(String depotPath, String filePath) = 4;
    void destroy() = 16777114;
}
