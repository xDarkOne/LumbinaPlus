package dev.lumbina.tv.core.domain.speech

interface ViddaVoiceSetup {
    enum class State { Missing, NeedsMicrophone, Ready, DebuggingRequired, Failed }

    fun status(): State

    /** Installs the verified voice service if necessary and grants microphone access over local ADB. */
    suspend fun prepare(): State
}
