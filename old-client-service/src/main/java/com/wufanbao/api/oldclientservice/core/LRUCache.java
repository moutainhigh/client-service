package com.wufanbao.api.oldclientservice.core;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User:Wangshihao
 * Date:2017-08-02
 * Time:14:27
 */
public class LRUCache<K, V> extends AbstractCacheMap<K, V> {

    public LRUCache(int cacheSize, long defaultExpire) {
        super(cacheSize, defaultExpire);
        //双向链表实现并保证线程安全
        this.cacheMap = new LinkedHashMap<K, CacheObject<K, V>>(cacheSize + 1, 1f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheObject<K, V>> eldest) {
                return LRUCache.this.removeEldestEntry(eldest);
            }
        };
    }

    private boolean removeEldestEntry(Map.Entry<K, CacheObject<K, V>> eldest) {

        if (cacheSize == 0)
            return false;

        return size() > cacheSize;
    }

    /**
     * 清理过期对象
     */

    @Override
    protected int eliminateCache() {
        if (!isNeedClearExpiredObject()) {
            return 0;
        }
        //开启一个迭代器
        Iterator<CacheObject<K, V>> iterator = cacheMap.values().iterator();
        int count = 0;
        while (iterator.hasNext()) {
            CacheObject<K, V> cacheObject = iterator.next();
            if (cacheObject.isExpired()) {
                iterator.remove();
                count++;
            }
        }
        return count;
    }
}
