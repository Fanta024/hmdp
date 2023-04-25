package com.hmdp.utils;

public interface Ilock {
     boolean tryLock(Long timeSec);
     void unLock();
}
