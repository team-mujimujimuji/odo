package com.mujimujimuji.odo.platform

import com.mujimujimuji.odo.engine.LatLng
import kotlinx.coroutines.flow.Flow

interface LocationSource {
    val location: Flow<LatLng>
}
