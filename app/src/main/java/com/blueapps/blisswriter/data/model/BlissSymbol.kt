package com.blueapps.blisswriter.data.model

/**
 * Rappresentazione di un singolo simbolo Bliss (carattere atomico o composto).
 *
 * @param bciAvId   ID univoco BCI-AV (es. 12335)
 * @param name      Nome canonico inglese (es. "person")
 * @param category  Categoria grammaticale: NOUN, VERB, ADJECTIVE, MODIFIER, INDICATOR, PUNCTUATION
 * @param svgPath   Path relativo all'asset SVG (es. "bliss/12335.svg")
 * @param isComposite  true se è composto da più caratteri base
 * @param compositeIds Lista ordinata di bciAvId dei componenti (vuota se atomico)
 */
data class BlissSymbol(
    val bciAvId: Int,
    val name: String,
    val category: BlissCategory,
    val svgPath: String,
    val isComposite: Boolean = false,
    val compositeIds: List<Int> = emptyList()
)

enum class BlissCategory {
    NOUN, VERB, ADJECTIVE, MODIFIER, INDICATOR, PUNCTUATION, UNKNOWN
}
