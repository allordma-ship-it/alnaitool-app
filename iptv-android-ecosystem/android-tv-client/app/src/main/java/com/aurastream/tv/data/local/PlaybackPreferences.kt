package com.aurastream.tv.data.local

/** Live stream container preference (MPEG-TS vs HLS). */
enum class StreamFormat { TS, HLS }

/** Video surface sizing: letterbox, stretch, crop. */
enum class AspectMode { FIT, FILL, ZOOM }
