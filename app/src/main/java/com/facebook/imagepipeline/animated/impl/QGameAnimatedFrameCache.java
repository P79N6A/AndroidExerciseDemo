package com.facebook.imagepipeline.animated.impl;

import android.net.Uri;
import android.support.annotation.GuardedBy;
import android.support.annotation.Nullable;

import com.facebook.cache.common.CacheKey;
import com.facebook.common.internal.Objects;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.internal.VisibleForTesting;
import com.facebook.common.references.CloseableReference;
import com.facebook.common.util.HashCodeUtil;
import com.facebook.imagepipeline.cache.CountingMemoryCache;
import com.facebook.imagepipeline.common.ImageDecodeOptions;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.common.RotationOptions;
import com.facebook.imagepipeline.image.CloseableImage;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * @author cainjiang
 * @date 2018/12/28
 */
public class QGameAnimatedFrameCache {
    @VisibleForTesting
    static class FrameKey implements CacheKey {

        private CacheKey mImageCacheKey;
        private int mFrameIndex;
        private String mSourceString;
        private @Nullable
        ResizeOptions mResizeOptions;
        private RotationOptions mRotationOptions;
        private ImageDecodeOptions mImageDecodeOptions;
        private int mHash;

        public FrameKey(CacheKey imageCacheKey, int frameIndex) {
            mImageCacheKey = imageCacheKey;
            mFrameIndex = frameIndex;
        }

        public FrameKey(String sourceString,
                        ResizeOptions resizeOptions,
                        RotationOptions rotationOptions,
                        ImageDecodeOptions imageDecodeOptions,
                        int frameIndex) {
            mSourceString = Preconditions.checkNotNull(sourceString);
            mResizeOptions = resizeOptions;
            mRotationOptions = rotationOptions;
            mImageDecodeOptions = imageDecodeOptions;
            mHash = HashCodeUtil.hashCode(
                    sourceString.hashCode(),
                    (resizeOptions != null) ? resizeOptions.hashCode() : 0,
                    rotationOptions.hashCode(),
                    mImageDecodeOptions);
            mFrameIndex = frameIndex;
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("imageCacheKey", mImageCacheKey)
                    .add("frameIndex", mFrameIndex)
                    .toString();
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (o instanceof QGameAnimatedFrameCache.FrameKey) {
                QGameAnimatedFrameCache.FrameKey that = (QGameAnimatedFrameCache.FrameKey) o;
                return (this.mFrameIndex == that.mFrameIndex
                        && this.mImageCacheKey.equals(that.mImageCacheKey)) ||
                        (this.mFrameIndex == that.mFrameIndex && mHash == that.mHash &&
                                mSourceString.equals(that.mSourceString) &&
                                Objects.equal(this.mResizeOptions, that.mResizeOptions) &&
                                Objects.equal(this.mRotationOptions, that.mRotationOptions) &&
                                Objects.equal(mImageDecodeOptions, that.mImageDecodeOptions));
            }
            return false;
        }

        @Override
        public int hashCode() {
            return mImageCacheKey.hashCode() * 1013 + mFrameIndex;
        }

        @Override
        public boolean containsUri(Uri uri) {
            return mImageCacheKey.containsUri(uri);
        }

        @Override
        public @Nullable
        String getUriString() {
            return null;
        }
    }

    private final CacheKey mImageCacheKey;
    private final CountingMemoryCache<CacheKey, CloseableImage> mBackingCache;
    private final CountingMemoryCache.EntryStateObserver<CacheKey> mEntryStateObserver;
    @GuardedBy("this")
    private final LinkedHashSet<CacheKey> mFreeItemsPool;

    public QGameAnimatedFrameCache(
            CacheKey imageCacheKey,
            final CountingMemoryCache<CacheKey, CloseableImage> backingCache) {
        mImageCacheKey = imageCacheKey;
        mBackingCache = backingCache;
        mFreeItemsPool = new LinkedHashSet<>();
        mEntryStateObserver = new CountingMemoryCache.EntryStateObserver<CacheKey>() {
            @Override
            public void onExclusivityChanged(CacheKey key, boolean isExclusive) {
                QGameAnimatedFrameCache.this.onReusabilityChange(key, isExclusive);
            }
        };
    }

    public synchronized void onReusabilityChange(CacheKey key, boolean isReusable) {
        if (isReusable) {
            mFreeItemsPool.add(key);
        } else {
            mFreeItemsPool.remove(key);
        }
    }

    /**
     * Caches the image for the given frame index.
     *
     * <p> Important: the client should use the returned reference instead of the original one.
     * It is the caller's responsibility to close the returned reference once not needed anymore.
     *
     * @return the new reference to be used, null if the value cannot be cached
     */
    @Nullable
    public CloseableReference<CloseableImage> cache(
            int frameIndex,
            CloseableReference<CloseableImage> imageRef) {
        return mBackingCache.cache(keyFor(frameIndex), imageRef, mEntryStateObserver);
    }

    /**
     * Gets the image for the given frame index.
     *
     * <p> It is the caller's responsibility to close the returned reference once not needed anymore.
     */
    @Nullable
    public CloseableReference<CloseableImage> get(int frameIndex) {
        return mBackingCache.get(keyFor(frameIndex));
    }

    /**
     * Check whether the cache contains an image for the given frame index.
     */
    public boolean contains(int frameIndex) {
        return mBackingCache.contains(keyFor(frameIndex));
    }

    /**
     * Gets the image to be reused, or null if there is no such image.
     *
     * <p> The returned image is the least recently used image that has no more clients referencing
     * it, and it has not yet been evicted from the cache.
     *
     * <p> The client can freely modify the bitmap of the returned image and can cache it again
     * without any restrictions.
     */
    @Nullable
    public CloseableReference<CloseableImage> getForReuse() {
        while (true) {
            CacheKey key = popFirstFreeItemKey();
            if (key == null) {
                return null;
            }
            CloseableReference<CloseableImage> imageRef = mBackingCache.reuse(key);
            if (imageRef != null) {
                return imageRef;
            }
        }
    }

    @Nullable
    private synchronized CacheKey popFirstFreeItemKey() {
        CacheKey cacheKey = null;
        Iterator<CacheKey> iterator = mFreeItemsPool.iterator();
        if (iterator.hasNext()) {
            cacheKey = iterator.next();
            iterator.remove();
        }
        return cacheKey;
    }

    private AnimatedFrameCache.FrameKey keyFor(int frameIndex) {
        return new AnimatedFrameCache.FrameKey(mImageCacheKey, frameIndex);
    }
}
