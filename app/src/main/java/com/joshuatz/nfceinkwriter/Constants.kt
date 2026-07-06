package com.joshuatz.nfceinkwriter

const val PackageName = "com.joshuatz.nfceinkwriter"

const val WaveShareUID = "WSDZ10m"

// Order matches WS SDK Enum (except off by 1, due to zero-index)
// @see https://www.waveshare.com/wiki/Android_SDK_for_NFC-Powered_e-Paper
// @see https://github.com/RfidResearchGroup/proxmark3/blob/0d1f8ca957c0ae6f3039237889cdabe5921afe2d/client/src/cmdhfwaveshare.c#L81-L90
// Display order for the picker. This is decoupled from the WaveShare NfcA SDK
// enum (see ScreenSizeToWsEnum), so it can be reordered freely.
val ScreenSizes = arrayOf(
    "1.54\"",
    "2.13\"",
    "2.9\"",
    "4.2\"",
    "7.5\"",
    "7.5\" HD",
    "2.7\"",
    "2.9\" v.B",
)

val DefaultScreenSize = "2.9\""

// Maps a screen size to its 1-based index in the WaveShare NfcA SDK enum. Only
// used by the legacy NfcA flash path; IsoDep displays (e.g. 1.54") don't use it.
val ScreenSizeToWsEnum = mapOf(
    "2.13\"" to 1,
    "2.9\"" to 2,
    "4.2\"" to 3,
    "7.5\"" to 4,
    "7.5\" HD" to 5,
    "2.7\"" to 6,
    "2.9\" v.B" to 7,
    "1.54\"" to 0,
)

val ScreenSizesInPixels = mapOf(
    // The true resolution for 2.13" is 250x122, but there is a (likely) typo in the SDK
    // @see https://github.com/joshuatz/nfc-epaper-writer/issues/2
    "2.13\"" to Pair(250, 128),
    "2.9\"" to Pair(296, 128),
    "4.2\"" to Pair(400, 300),
    "7.5\"" to Pair(800, 480),
    "7.5\" HD" to Pair(880, 528),
    "2.7\"" to Pair(264, 176),
    "2.9\" v.B" to Pair(296, 128),
    // Square panel — a 200x200 canvas avoids non-uniform scaling at flash time.
    "1.54\"" to Pair(200, 200),
)

object Constants {
    var Preference_File_Key = "Preferences"
    var PreferenceKeys = PrefKeys
}

object PrefKeys {
    var DisplaySize = "Display_Size"
    var GeneratedImgPath = "Generated_Image_Path"
    var DitherEnabled = "Dither_Enabled"
}

object IntentKeys {
    var GeneratedImgPath = "$PackageName.imgUri"
    var GeneratedImgMime = "$PackageName.imgMime"
}

val GeneratedImageFilename = "generated.png"