package com.edureminder.quicktube.core.extractor

import java.util.Locale

internal actual fun deviceCountry(scope: org.koin.core.scope.Scope): String = Locale.getDefault().country.uppercase().ifBlank { "US" }
