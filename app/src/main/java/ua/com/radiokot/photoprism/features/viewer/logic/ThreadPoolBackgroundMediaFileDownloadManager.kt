package ua.com.radiokot.photoprism.features.viewer.logic

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.gallery.data.model.GalleryMedia
import ua.com.radiokot.photoprism.features.gallery.logic.DownloadFileUseCase
import java.io.File
import java.util.concurrent.Executors

/**
 * A [BackgroundMediaFileDownloadManager] which utilizes a fixed thread pool.
 */
class ThreadPoolBackgroundMediaFileDownloadManager(
    private val downloadFileUseCaseFactory: DownloadFileUseCase.Factory,
    poolSize: Int,
) : BackgroundMediaFileDownloadManager {
    private val log = kLogger("RxBackgroundMFDownloadManager")
    private val downloads =
        mutableMapOf<String, Pair<Observable<BackgroundMediaFileDownloadManager.Progress>, Disposable>>()
    private val scheduler = Schedulers.from(Executors.newFixedThreadPool(poolSize))

    override fun enqueue(
        file: GalleryMedia.File,
        destination: File
    ): Observable<BackgroundMediaFileDownloadManager.Progress> {
        val key = file.mediaUid

        return Observable
            // At first, publish the indeterminate progress,
            // without waiting for a free thread from the pool.
            .just(BackgroundMediaFileDownloadManager.Progress.INDETERMINATE)
            .mergeWith(
                // Then merge it with the actual download progress,
                // which execution may be delayed.
                downloadFileUseCaseFactory
                    .get(
                        url = file.downloadUrl,
                        destination = destination,
                        mimeType = file.mimeType
                    )
                    .perform()
                    .subscribeOn(scheduler)
                    .map { progress ->
                        BackgroundMediaFileDownloadManager.Progress(
                            percent = progress.percent,
                        )
                    }
            )
            .doOnError {
                log.error(it) {
                    "enqueue(): download_error_occurred"
                }
            }
            .doOnComplete {
                log.debug {
                    "enqueue(): download_complete"
                }

                downloads.remove(key)
            }
            // Ignore errors to comply with the interface contract.
            .onErrorComplete()
            // Replay the last progress update for all new subscribers,
            // so the UI can set the latest state immediately.
            .replay(1)
            .also {
                // Start now, making the observable hot.
                downloads[key] = it to it.connect()

                log.debug {
                    "enqueue(): enqueued:" +
                            "\nfile=$file," +
                            "\ndestination=$destination"
                }
            }
    }

    override fun cancel(mediaUid: String) {
        val disposable = downloads[mediaUid]?.second

        if (disposable == null) {
            log.debug {
                "cancel(): download_not_found:" +
                        "\nmediaUid=$mediaUid"
            }
            return
        }

        if (disposable.isDisposed) {
            log.debug {
                "cancel(): download_already_ended:" +
                        "\nmediaUid=$mediaUid"
            }
            return
        }

        disposable.dispose()

        log.debug {
            "cancel(): download_canceled:" +
                    "\nmediaUid=$mediaUid"
        }
    }

    override fun getProgress(mediaUid: String): Observable<BackgroundMediaFileDownloadManager.Progress> {
        return downloads[mediaUid]
            ?.first
            ?: Observable.empty()
    }
}