package ua.com.radiokot.photoprism.features.gallery.view

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.extension.addToCloseables
import ua.com.radiokot.photoprism.extension.checkNotNull
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.gallery.data.model.GalleryMedia
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryListItem
import java.io.File
import java.text.DateFormat
import java.util.*

class GalleryViewModel(
    private val galleryMediaRepositoryFactory: SimpleGalleryMediaRepository.Factory,
    private val dateHeaderDayYearDateFormat: DateFormat,
    private val dateHeaderDayDateFormat: DateFormat,
) : ViewModel() {
    private val log = kLogger("GalleryVM")
    private lateinit var galleryMediaRepository: SimpleGalleryMediaRepository

    val isLoading: MutableLiveData<Boolean> = MutableLiveData(false)
    val itemsList: MutableLiveData<List<GalleryListItem>?> = MutableLiveData(null)
    private val eventsSubject: PublishSubject<Event> = PublishSubject.create()
    val events: Observable<Event> = eventsSubject
    val state: MutableLiveData<State> = MutableLiveData()

    private lateinit var downloadMediaFileViewModel: DownloadMediaFileViewModel

    fun initSelection(
        downloadViewModel: DownloadMediaFileViewModel,
        requestedMimeType: String?,
    ) {
        val filterMediaType = when {
            requestedMimeType == null ->
                null
            requestedMimeType.startsWith("image/") ->
                GalleryMedia.TypeName.IMAGE
            requestedMimeType.startsWith("video/") ->
                GalleryMedia.TypeName.VIDEO
            else ->
                null
        }

        downloadMediaFileViewModel = downloadViewModel

        galleryMediaRepository = galleryMediaRepositoryFactory.getFiltered(filterMediaType)
        subscribeToRepository()

        log.debug {
            "initSelection(): initialized_selection:" +
                    "\nrequestedMimeType=$requestedMimeType," +
                    "\nmatchedFilterMediaType=$filterMediaType"
        }

        state.value = State.Selecting(filter = filterMediaType)

        update()
    }

    fun initViewing(
        downloadViewModel: DownloadMediaFileViewModel,
    ) {
        downloadMediaFileViewModel = downloadViewModel

        galleryMediaRepository = galleryMediaRepositoryFactory.getFiltered(null)
        subscribeToRepository()

        log.debug {
            "initViewing(): initialized_viewing"
        }

        state.value = State.Viewing

        update()
    }

    private fun subscribeToRepository() {
        galleryMediaRepository.items
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(::onNewRepositoryItems)
            .addToCloseables(this)

        galleryMediaRepository.loading
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(isLoading::setValue)
            .addToCloseables(this)

        galleryMediaRepository.errors
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                log.error(it) { "subscribeToRepository(): error_occurred" }
            }
            .addToCloseables(this)
    }

    private fun onNewRepositoryItems(galleryMediaItems: List<GalleryMedia>) {
        val newListItems = mutableListOf<GalleryListItem>()

        // Add date headers.
        val today = Date()
        galleryMediaItems
            .forEachIndexed { i, galleryMedia ->
                val takenAt = galleryMedia.takenAt

                if (i == 0 || !takenAt.isSameDayAs(galleryMediaItems[i - 1].takenAt)) {
                    newListItems.add(
                        if (takenAt.isSameDayAs(today))
                            GalleryListItem.Header(
                                textRes = R.string.today,
                                identifier = takenAt.time,
                            )
                        else {
                            val formattedDate =
                                if (takenAt.isSameYearAs(today))
                                    dateHeaderDayDateFormat.format(takenAt)
                                else
                                    dateHeaderDayYearDateFormat.format(takenAt)

                            GalleryListItem.Header(
                                text = formattedDate.replaceFirstChar {
                                    if (it.isLowerCase())
                                        it.titlecase(Locale.getDefault())
                                    else
                                        it.toString()
                                },
                                identifier = takenAt.time,
                            )
                        }
                    )
                }

                newListItems.add(
                    GalleryListItem.Media(
                        source = galleryMedia,
                    )
                )
            }

        itemsList.value = newListItems
    }

    private fun Date.isSameDayAs(other: Date): Boolean {
        val calendar = Calendar.getInstance()
        calendar.time = this
        val thisYear = calendar[Calendar.YEAR]
        val thisDay = calendar[Calendar.DAY_OF_YEAR]
        calendar.time = other
        val otherYear = calendar[Calendar.YEAR]
        val otherDay = calendar[Calendar.DAY_OF_YEAR]

        return thisYear == otherYear && thisDay == otherDay
    }

    private fun Date.isSameYearAs(other: Date): Boolean {
        val calendar = Calendar.getInstance()
        calendar.time = this
        val thisYear = calendar[Calendar.YEAR]
        calendar.time = other
        val otherYear = calendar[Calendar.YEAR]

        return thisYear == otherYear
    }

    private fun update() {
        galleryMediaRepository.updateIfNotFresh()
    }

    fun loadMore() {
        if (!galleryMediaRepository.isLoading) {
            log.debug { "loadMore(): requesting_load_more" }
            galleryMediaRepository.loadMore()
        }
    }

    fun onItemClicked(item: GalleryListItem) {
        log.debug {
            "onItemClicked(): gallery_item_clicked:" +
                    "\nitem=$item"
        }

        if (item !is GalleryListItem.Media) {
            return
        }

        when (state.value.checkNotNull()) {
            is State.Selecting -> {
                if (item.source != null) {
                    if (item.source.files.size > 1) {
                        openFileSelectionDialog(item.source.files)
                    } else {
                        downloadAndReturnFile(item.source.files.firstOrNull().checkNotNull {
                            "There must be at least one file in the gallery media object"
                        })
                    }
                }
            }

            is State.Viewing ->
                if (item.source != null) {
                    openViewer(item.source)
                }
        }
    }

    private fun openFileSelectionDialog(files: List<GalleryMedia.File>) {
        log.debug {
            "openFileSelectionDialog(): posting_open_event:" +
                    "\nfiles=$files"
        }

        eventsSubject.onNext(Event.OpenFileSelectionDialog(files))
    }

    fun onFileSelected(file: GalleryMedia.File) {
        log.debug {
            "onFileSelected(): file_selected:" +
                    "\nfile=$file"
        }

        downloadAndReturnFile(file)
    }

    private fun downloadAndReturnFile(file: GalleryMedia.File) {
        downloadMediaFileViewModel.downloadFile(
            file = file,
            onSuccess = { destinationFile ->
                eventsSubject.onNext(
                    Event.ReturnDownloadedFile(
                        downloadedFile = destinationFile,
                        mimeType = file.mimeType,
                        displayName = File(file.name).name
                    )
                )
            }
        )
    }

    private fun openViewer(media: GalleryMedia) {
        val index = galleryMediaRepository.itemsList.indexOf(media)
        val repositoryQuery = galleryMediaRepository.query

        log.debug {
            "openViewer(): opening_viewer:" +
                    "\nmedia=$media," +
                    "\nindex=$index," +
                    "\nrepositoryQuery=$repositoryQuery"
        }

        eventsSubject.onNext(
            Event.OpenViewer(
                mediaIndex = index,
                repositoryQuery = repositoryQuery,
            )
        )
    }

    sealed interface Event {
        class OpenFileSelectionDialog(val files: List<GalleryMedia.File>) : Event

        class ReturnDownloadedFile(
            val downloadedFile: File,
            val mimeType: String,
            val displayName: String,
        ) : Event

        class OpenViewer(
            val mediaIndex: Int,
            val repositoryQuery: String?,
        ) : Event
    }

    sealed interface State {
        object Viewing : State
        class Selecting(val filter: GalleryMedia.TypeName?) : State
    }
}