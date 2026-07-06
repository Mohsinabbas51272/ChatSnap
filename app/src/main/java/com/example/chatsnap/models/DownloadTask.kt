package com.example.chatsnap.models

data class DownloadTask(
    val id: String,
    val url: String,
    val formatLabel: String,
    val formatOption: String,
    val isPlaylist: Boolean,
    var status: Status = Status.QUEUED,
    var title: String = "Queued Video...",
    var progress: Int = 0,
    var speed: String = "-- MB/s",
    var eta: String = "--:--",
    var size: String = "-- MB",
    var tempFilePath: String? = null,
    var thumbnailUrl: String? = null,
    var userId: String? = null
) {
    enum class Status { QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED }
}
