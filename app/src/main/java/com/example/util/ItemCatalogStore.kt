package com.example.util

import android.content.Context
import com.example.data.model.MarketPurchase
import com.example.data.model.Order

object ItemCatalogStore {

  private const val PREFS_NAME = "orderbuddy_item_catalog"
  private const val KEY_CUSTOM_ITEMS = "custom_items"
  private const val KEY_ORDERED_ITEMS = "custom_items_ordered"

  val DEFAULT_ENGLISH_ITEMS: List<String> = listOf(
    "Rice",
    "Soybean Oil",
    "Mustard Oil",
    "Onion",
    "Potato",
    "Garlic",
    "Ginger",
    "Green Chili",
    "Tomato",
    "Lentils",
    "Moong Dal",
    "Milk",
    "Eggs",
    "Bread",
    "Chicken",
    "Beef",
    "Fish",
    "Sugar",
    "Salt",
    "Turmeric Powder",
    "Chili Powder",
    "Tea",
    "Soap",
    "Shampoo",
    "Toothpaste",
    "Detergent",
    "Biscuits",
    "Banana",
    "Cucumber",
    "Lemon"
  )

  val DEFAULT_BENGALI_ITEMS: List<String> = listOf(
    "চাল",
    "সয়াবিন তেল",
    "সরিষার তেল",
    "পেঁয়াজ",
    "আলু",
    "রসুন",
    "আদা",
    "কাঁচা মরিচ",
    "টমেটো",
    "মসুর ডাল",
    "মুগ ডাল",
    "দুধ",
    "ডিম",
    "পাউরুটি",
    "মুরগির মাংস",
    "গরুর মাংস",
    "মাছ",
    "চিনি",
    "লবণ",
    "হলুদ গুঁড়া",
    "মরিচ গুঁড়া",
    "চা পাতা",
    "সাবান",
    "শ্যাম্পু",
    "টুথপেস্ট",
    "ডিটারজেন্ট",
    "বিস্কুট",
    "কলা",
    "শসা",
    "লেবু"
  )

  val DEFAULT_ITEMS: List<String> = DEFAULT_ENGLISH_ITEMS + DEFAULT_BENGALI_ITEMS

  fun getCustomItems(context: Context): List<String> {
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val orderedRaw = prefs.getString(KEY_ORDERED_ITEMS, null)
    if (!orderedRaw.isNullOrBlank()) {
      return orderedRaw.split("|||").filter { it.isNotBlank() }
    }
    val set = prefs.getStringSet(KEY_CUSTOM_ITEMS, emptySet()) ?: emptySet()
    return set.toList()
  }

  fun saveCustomItem(context: Context, name: String) {
    val cleanNameRaw = InputSanitizer.sanitizeItemName(name)
    if (!InputSanitizer.isValidItemName(cleanNameRaw)) return
    val cleanName = cleanNameRaw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    val appContext = context.applicationContext
    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Load existing ordered list
    val currentList = getCustomItems(context)
      .map { InputSanitizer.sanitizeItemName(it) }
      .filter { InputSanitizer.isValidItemName(it) }
      .toMutableList()
    // Remove if existing (case-insensitive) to move to the very front
    currentList.removeAll { it.equals(cleanName, ignoreCase = true) }
    currentList.add(0, cleanName)

    val boundedList = currentList.take(200)
    val updatedJoined = boundedList.joinToString("|||")
    val updatedSet = LinkedHashSet(boundedList)

    prefs.edit()
      .putString(KEY_ORDERED_ITEMS, updatedJoined)
      .putStringSet(KEY_CUSTOM_ITEMS, updatedSet)
      .commit()
  }

  fun containsBengali(text: String): Boolean = text.any { it in '\u0980'..'\u09FF' }

  private val LEGACY_BILINGUAL_REGEX = Regex("""^([A-Za-z0-9\s\-&/]+)\s*\(([\u0980-\u09FF\s]+)\)$""")

  private fun expandIfLegacyBilingual(item: String): List<String> {
    val match = LEGACY_BILINGUAL_REGEX.matchEntire(item.trim())
    return if (match != null) {
      val en = match.groupValues[1].trim()
      val bn = match.groupValues[2].trim()
      listOf(en, bn).filter { InputSanitizer.isValidItemName(it) }
    } else {
      listOf(item)
    }
  }

  fun getAllSuggestions(
    context: Context,
    myOrders: List<Order> = emptyList(),
    partnerOrders: List<Order> = emptyList(),
    adminOrders: List<Order> = emptyList(),
    marketPurchases: List<MarketPurchase> = emptyList()
  ): List<String> {
    val result = linkedSetOf<String>()

    // 1. User's saved custom items first
    val customItems = getCustomItems(context)
      .map { InputSanitizer.sanitizeItemName(it) }
      .filter { InputSanitizer.isValidItemName(it) }
      .flatMap { expandIfLegacyBilingual(it) }
    result.addAll(customItems)

    // 2. Items from orders
    val orderItems = (myOrders + partnerOrders + adminOrders)
      .flatMap { it.typedItems.map { item -> InputSanitizer.sanitizeItemName(item.name) } }
      .filter { InputSanitizer.isValidItemName(it) }
      .flatMap { expandIfLegacyBilingual(it) }
    result.addAll(orderItems)

    // 3. Items from market purchases
    val marketItems = marketPurchases
      .map { InputSanitizer.sanitizeItemName(it.itemName) }
      .filter { InputSanitizer.isValidItemName(it) }
      .flatMap { expandIfLegacyBilingual(it) }
    result.addAll(marketItems)

    // 4. Default comprehensive grocery items (English and Bengali separated)
    result.addAll(DEFAULT_ITEMS)

    // Deduplicate case-insensitively while preserving formatted casing
    val seenLower = mutableSetOf<String>()
    val deduped = mutableListOf<String>()
    for (item in result) {
      val lower = item.lowercase()
      if (!seenLower.contains(lower)) {
        seenLower.add(lower)
        deduped.add(item)
      }
    }

    return deduped
  }
}
