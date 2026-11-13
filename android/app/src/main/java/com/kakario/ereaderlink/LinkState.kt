package com.kakario.ereaderlink

/** 화면에 보여줄 연결 상태 (서비스가 쓰고, 화면이 1초마다 읽음) */
object LinkState {
    const val HTTP_PORT = 47820       // 파일 서버
    const val DISCOVERY_PORT = 47821  // PC가 "이북리더 있니?" 묻는 곳
    const val ANNOUNCE_PORT = 47822   // 이북리더가 "나 여기 있어" 알리는 곳

    @Volatile var running = false
    @Volatile var pcAddress: String? = null
    @Volatile var lastSeen = 0L
    @Volatile var activity: String? = null   // 지금 하고 있는 일 (예: "받는 중: 책.epub")
    @Volatile var activityAt = 0L
    @Volatile var error: String? = null

    fun touch(ip: String?) {
        if (ip != null) pcAddress = ip
        lastSeen = System.currentTimeMillis()
    }

    fun doing(text: String) {
        activity = text
        activityAt = System.currentTimeMillis()
    }

    val connected: Boolean get() = System.currentTimeMillis() - lastSeen < 8000
}
