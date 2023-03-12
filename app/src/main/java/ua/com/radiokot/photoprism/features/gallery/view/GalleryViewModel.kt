package ua.com.radiokot.photoprism.features.gallery.view

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaListItem

class GalleryViewModel(
    private val galleryMediaRepository: SimpleGalleryMediaRepository,
) : ViewModel() {
    private val compositeDisposable = CompositeDisposable()

    val isLoading: MutableLiveData<Boolean> = MutableLiveData(false)
    val itemsList: MutableLiveData<List<GalleryMediaListItem>?> = MutableLiveData(null)

    init {
        subscribeToRepository()

        galleryMediaRepository.updateIfNotFresh()
    }

    private fun subscribeToRepository() {
        galleryMediaRepository.items
            .observeOn(AndroidSchedulers.mainThread())
            .map { galleryMediaItems ->
                galleryMediaItems.map(::GalleryMediaListItem)
            }
            .subscribe(itemsList::setValue)
            .addTo(compositeDisposable)

        galleryMediaRepository.loading
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(isLoading::setValue)
            .addTo(compositeDisposable)
    }

    override fun onCleared() {
        compositeDisposable.dispose()
        super.onCleared()
    }
}