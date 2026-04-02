package chimahon.dictionary

interface TextTypeDetector {
    fun detect(text: String): TextType
}

enum class TextType {
    JAPANESE,
    ARABIC,
}

class SimpleTextTypeDetector : TextTypeDetector {
    private val arabicRange = Regex("[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]")

    override fun detect(text: String): TextType {
        return if (arabicRange.containsMatchIn(text)) TextType.ARABIC else TextType.JAPANESE
    }
}
