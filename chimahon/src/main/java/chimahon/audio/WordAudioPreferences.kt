package chimahon.audio

import tachiyomi.core.common.preference.Preference

interface WordAudioPreferences {
    fun wordAudioEnabled(): Preference<Boolean>
    fun wordAudioAutoplay(): Preference<Boolean>
    fun wordAudioSources(): Preference<String>
    fun wordAudioLocalPath(): Preference<String>
    fun wordAudioLocalEnabled(): Preference<Boolean>
}
