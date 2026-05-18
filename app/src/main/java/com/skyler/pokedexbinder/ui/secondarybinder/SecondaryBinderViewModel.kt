package com.skyler.pokedexbinder.ui.secondarybinder

import androidx.lifecycle.ViewModel
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class SecondaryBinderViewModel @Inject constructor(
    private val secondaryBinderDao: SecondaryBinderDao
) : ViewModel() {
    val entries: Flow<List<SecondaryBinderEntry>> = secondaryBinderDao.observeAll()
}
