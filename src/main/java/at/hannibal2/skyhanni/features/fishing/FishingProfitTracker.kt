package at.hannibal2.skyhanni.features.fishing

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.events.EntityMoveEvent
import at.hannibal2.skyhanni.events.FishingBobberCastEvent
import at.hannibal2.skyhanni.events.GuiRenderEvent
import at.hannibal2.skyhanni.events.ItemInHandChangeEvent
import at.hannibal2.skyhanni.events.LorenzChatEvent
import at.hannibal2.skyhanni.events.LorenzWorldChangeEvent
import at.hannibal2.skyhanni.events.RepositoryReloadEvent
import at.hannibal2.skyhanni.events.SackChangeEvent
import at.hannibal2.skyhanni.events.SkillExpGainEvent
import at.hannibal2.skyhanni.events.entity.ItemAddInInventoryEvent
import at.hannibal2.skyhanni.features.bazaar.BazaarApi.Companion.getBazaarData
import at.hannibal2.skyhanni.features.fishing.FishingAPI.isFishingRod
import at.hannibal2.skyhanni.test.PriceSource
import at.hannibal2.skyhanni.utils.DelayedRun
import at.hannibal2.skyhanni.utils.ItemUtils.nameWithEnchantment
import at.hannibal2.skyhanni.utils.KeyboardManager
import at.hannibal2.skyhanni.utils.LorenzUtils
import at.hannibal2.skyhanni.utils.LorenzUtils.addAsSingletonList
import at.hannibal2.skyhanni.utils.LorenzUtils.addSelector
import at.hannibal2.skyhanni.utils.LorenzUtils.sortedDesc
import at.hannibal2.skyhanni.utils.NEUInternalName
import at.hannibal2.skyhanni.utils.NEUInternalName.Companion.asInternalName
import at.hannibal2.skyhanni.utils.NEUItems.getItemStack
import at.hannibal2.skyhanni.utils.NEUItems.getNpcPriceOrNull
import at.hannibal2.skyhanni.utils.NEUItems.getPriceOrNull
import at.hannibal2.skyhanni.utils.NumberUtil
import at.hannibal2.skyhanni.utils.NumberUtil.addSeparators
import at.hannibal2.skyhanni.utils.NumberUtil.formatNumber
import at.hannibal2.skyhanni.utils.SimpleTimeMark
import at.hannibal2.skyhanni.utils.StringUtils.matchMatcher
import at.hannibal2.skyhanni.utils.StringUtils.removeColor
import at.hannibal2.skyhanni.utils.jsonobjects.FishingProfitItemsJson
import at.hannibal2.skyhanni.utils.renderables.Renderable
import at.hannibal2.skyhanni.utils.tracker.SkyHanniTracker
import at.hannibal2.skyhanni.utils.tracker.TrackerData
import com.google.gson.annotations.Expose
import io.github.moulberry.notenoughupdates.NotEnoughUpdates
import net.minecraft.client.Minecraft
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object FishingProfitTracker {
    private val config get() = SkyHanniMod.feature.fishing.fishingProfitTracker

    private val coinsChatPattern = ".* CATCH! §r§bYou found §r§6(?<coins>.*) Coins§r§b\\.".toPattern()
    private var lastClickDelay = 0L

    private var lastFishingTime = SimpleTimeMark.farPast()

    private val tracker =
        SkyHanniTracker("Fishing Profit Tracker", { Data() }, { it.fishing.fishingProfitTracker }) { drawDisplay(it) }

    class Data : TrackerData() {
        override fun reset() {
            items.clear()
            totalCatchAmount = 0
        }

        @Expose
        var items = mutableMapOf<NEUInternalName, FishingItem>()

        @Expose
        var totalCatchAmount = 0L

        class FishingItem {
            @Expose
            var internalName: NEUInternalName? = null

            @Expose
            var timesCaught: Long = 0

            @Expose
            var totalAmount: Long = 0

            @Expose
            var hidden = false

            override fun toString() = "FishingItem{" +
                    "internalName='" + internalName + '\'' +
                    ", timesDropped=" + timesCaught +
                    ", totalAmount=" + totalAmount +
                    ", hidden=" + hidden +
                    '}'

            var lastTimeUpdated = SimpleTimeMark.farPast()
        }
    }

    private val SKYBLOCK_COIN by lazy { "SKYBLOCK_COIN".asInternalName() }
    private val MAGMA_FISH by lazy { "MAGMA_FISH".asInternalName() }

    private fun drawDisplay(data: Data): List<List<Any>> = buildList {
        addAsSingletonList("§e§lFishing Profit Tracker")

        var profit = 0.0
        val map = mutableMapOf<Renderable, Long>()
        for ((internalName, itemProfit) in data.items) {
            val amount = itemProfit.totalAmount

            var pricePer = if (internalName == SKYBLOCK_COIN) 1.0 else getPrice(internalName)
            if (pricePer == 0.0) {
                pricePer = getPrice(MAGMA_FISH) * FishingAPI.getFilletPerTrophy(internalName)
            }

            val price = (pricePer * amount).toLong()
            val displayAmount = if (internalName == SKYBLOCK_COIN) {
                itemProfit.timesCaught
            } else amount

            val cleanName =
                if (internalName == SKYBLOCK_COIN) "§6Coins" else internalName.getItemStack().nameWithEnchantment
            var name = cleanName ?: error("no name for $internalName")
            val priceFormat = NumberUtil.format(price)
            val hidden = itemProfit.hidden

            val newDrop = itemProfit.lastTimeUpdated.passedSince() < 10.seconds && config.showRecentDropss
            val numberColor = if (newDrop) "§a§l" else "§7"

            if (hidden) {
                name = "§8§m" + name.removeColor(keepFormatting = true).replace("§r", "")
            }

            val text = " $numberColor${displayAmount.addSeparators()}x $name§7: §6$priceFormat"

            val timesCaught = itemProfit.timesCaught
            val percentage = timesCaught.toDouble() / data.totalCatchAmount
            val catchRate = LorenzUtils.formatPercentage(percentage.coerceAtMost(1.0))

            val renderable = if (tracker.isInventoryOpen()) Renderable.clickAndHover(
                text,
                buildLore(timesCaught, catchRate, hidden, newDrop)
            ) {
                if (System.currentTimeMillis() > lastClickDelay + 150) {

                    if (KeyboardManager.isControlKeyDown()) {
                        data.items.remove(internalName)
                        LorenzUtils.chat("§e[SkyHanni] Removed $cleanName §efrom Fishing Frofit Tracker.")
                        lastClickDelay = System.currentTimeMillis() + 500
                    } else {
                        itemProfit.hidden = !hidden
                        lastClickDelay = System.currentTimeMillis()
                    }
                    tracker.update()
                }
            } else Renderable.string(text)
            if (tracker.isInventoryOpen() || !hidden) {
                map[renderable] = price
            }
            profit += price
        }

        for (text in map.sortedDesc().keys) {
            addAsSingletonList(text)
        }

        val fishedCount = data.totalCatchAmount
        addAsSingletonList(
            Renderable.hoverTips(
                "§7Times fished: §e${fishedCount.addSeparators()}",
                listOf("§7You catched §e${fishedCount.addSeparators()} §7times something.")
            )
        )

        val profitFormat = NumberUtil.format(profit)
        val profitPrefix = if (profit < 0) "§c" else "§6"

        val profitPerCatch = profit / data.totalCatchAmount
        val profitPerCatchFormat = NumberUtil.format(profitPerCatch)

        val text = "§eTotal Profit: $profitPrefix$profitFormat"
        addAsSingletonList(Renderable.hoverTips(text, listOf("§7Profit per catch: $profitPrefix$profitPerCatchFormat")))

        if (tracker.isInventoryOpen()) {
            addSelector<PriceSource>(
                "",
                getName = { type -> type.displayName },
                isCurrent = { it.ordinal == config.priceFrom },
                onChange = {
                    config.priceFrom = it.ordinal
                    tracker.update()
                }
            )
        }
    }

    private fun buildLore(
        timesCaught: Long,
        catchRate: String,
        hidden: Boolean,
        newDrop: Boolean
    ) = buildList {
        add("§7Caught §e${timesCaught.addSeparators()} §7times.")
        add("§7Your catch rate: §c$catchRate")
        add("")
        if (newDrop) {
            add("§aYou caught this item recently.")
            add("")
        }
        add("§eClick to " + (if (hidden) "show" else "hide") + "!")
        add("§eControl + Click to remove this item!")
    }

    @SubscribeEvent
    fun onSackChange(event: SackChangeEvent) {
        if (!isEnabled()) return

        for (sackChange in event.sackChanges) {
            val change = sackChange.delta
            if (change > 0) {
                val internalName = sackChange.internalName
                maybeAddItem(internalName, change)
            }
        }
    }

    @SubscribeEvent
    fun onItemAdd(event: ItemAddInInventoryEvent) {
        if (!isEnabled()) return

        DelayedRun.runDelayed(500.milliseconds) {
            maybeAddItem(event.internalName, event.amount)
        }
    }

    @SubscribeEvent
    fun onChat(event: LorenzChatEvent) {
        coinsChatPattern.matchMatcher(event.message) {
            val coins = group("coins").formatNumber()
            addItem(SKYBLOCK_COIN, coins.toInt())
        }
    }

    private fun addItem(internalName: NEUInternalName, stackSize: Int) {
        tracker.modify {
            it.totalCatchAmount++

            val fishingItem = it.items.getOrPut(internalName) { Data.FishingItem() }

            fishingItem.timesCaught++
            fishingItem.totalAmount += stackSize
            fishingItem.lastTimeUpdated = SimpleTimeMark.now()
        }
    }

    @SubscribeEvent
    fun onItemInHandChange(event: ItemInHandChangeEvent) {
        if (event.oldItem.isFishingRod()) {
            lastFishingTime = SimpleTimeMark.now()
        }
        if (event.newItem.isFishingRod()) {
            DelayedRun.runDelayed(1.seconds) {
                lastFishingTime = SimpleTimeMark.now()
            }
        }
    }

    @SubscribeEvent
    fun onSkillExpGain(event: SkillExpGainEvent) {
        val skill = event.skill
        if (isEnabled()) {
            if (skill != "fishing") {
                lastFishingTime = SimpleTimeMark.farPast()
            }
        }
    }

    @SubscribeEvent
    fun onRenderOverlay(event: GuiRenderEvent) {
        if (!isEnabled()) return
        if (!FishingAPI.hasFishingRodInHand()) return
        if (isMoving && config.hideMoving) return

        tracker.renderDisplay(config.position)
    }

    private fun maybeAddItem(internalName: NEUInternalName, amount: Int) {
        if (lastFishingTime.passedSince() > 10.minutes) return

        if (!isAllowedItem(internalName)) {
            LorenzUtils.debug("Ignored non-fishing item pickup: $internalName'")
            return
        }

        addItem(internalName, amount)
    }

    private var itemCategories = mapOf<String, List<NEUInternalName>>()

    private var shItemCategories = mapOf<String, List<NEUInternalName>>()
    private var neuItemCategories = mapOf<String, List<NEUInternalName>>()

    @SubscribeEvent
    fun onRepoReload(event: RepositoryReloadEvent) {
        shItemCategories = event.getConstant<FishingProfitItemsJson>("FishingProfitItems").categories
        updateItemCategories()
    }

    private fun updateItemCategories() {
        itemCategories = shItemCategories + neuItemCategories
    }

    @SubscribeEvent
    fun onNeuRepoReload(event: io.github.moulberry.notenoughupdates.events.RepositoryReloadEvent) {
        val totalDrops = mutableListOf<String>()
        val dropCategories = mutableMapOf<String, MutableList<NEUInternalName>>()
        for ((seaCreature, data) in NotEnoughUpdates.INSTANCE.manager.itemInformation.filter { it.key.endsWith("_SC") }) {
            val asJsonObject = data.getAsJsonArray("recipes")[0].asJsonObject
            val drops = asJsonObject.getAsJsonArray("drops")
                .map { it.asJsonObject.get("id").asString }.map { it.split(":").first() }
            val asJsonArray = asJsonObject.get("extra")
            val extra = asJsonArray?.let {
                asJsonArray.asJsonArray.toList()
                    .map { it.toString() }
                    .filter { !it.contains("Fishing Skill") && !it.contains("Requirements:") && !it.contains("Fished from water") }
                    .joinToString(" + ")
            } ?: "null"
            val category = if (extra.contains("Fishing Festival")) {
                "Fishing Festival"
            } else if (extra.contains("Spooky Festival")) {
                "Spooky Festival"
            } else if (extra.contains("Jerry's Workshop")) {
                "Jerry's Workshop"
            } else if (extra.contains("Oasis")) {
                "Oasis"
            } else if (extra.contains("Magma Fields") || extra.contains("Precursor Remnants") ||
                extra.contains("Goblin Holdout")
            ) {
                "Crystal Hollows"
            } else if (extra.contains("Crimson Isle Lava")) {
                "Crimson Isle Lava"
            } else {
                if (extra.isNotEmpty()) {
                    println("unknown extra: $extra = $seaCreature ($drops)")
                }
                "Water"
            } + " Sea Creatures"
            for (drop in drops) {
                if (drop !in totalDrops) {
                    totalDrops.add(drop)
                    dropCategories.getOrPut(category) { mutableListOf() }.add(drop.asInternalName())
                }
            }
        }
        neuItemCategories = dropCategories
        updateItemCategories()
    }

    private fun isAllowedItem(internalName: NEUInternalName) = itemCategories.any { internalName in it.value }

    private fun getPrice(internalName: NEUInternalName) = when (config.priceFrom) {
        0 -> internalName.getBazaarData()?.sellPrice ?: internalName.getPriceOrNull() ?: 0.0
        1 -> internalName.getBazaarData()?.buyPrice ?: internalName.getPriceOrNull() ?: 0.0

        else -> internalName.getNpcPriceOrNull() ?: 0.0
    }

    /// <editor-fold desc="isMoving">

    private val lastSteps = mutableListOf<Double>()
    private var isMoving = true

    @SubscribeEvent
    fun onEntityMove(event: EntityMoveEvent) {
        if (!isEnabled() || !config.hideMoving) return
        if (event.entity != Minecraft.getMinecraft().thePlayer) return

        val distance = event.newLocation.distanceIgnoreY(event.oldLocation)
        if (distance < 0.1) {
            lastSteps.clear()
            return
        }
        lastSteps.add(distance)
        if (lastSteps.size > 20) {
            lastSteps.removeAt(0)
        }
        val total = lastSteps.sum()
        if (total > 3) {
            isMoving = true
        }
    }

    @SubscribeEvent
    fun onBobberThrow(event: FishingBobberCastEvent) {
        if (!isEnabled() || !config.hideMoving) return
        isMoving = false
        tracker.firstUpdate()
    }

    @SubscribeEvent
    fun onWorldChange(event: LorenzWorldChangeEvent) {
        isMoving = true
    }
    /// </editor-fold>

    fun resetCommand(args: Array<String>) {
        tracker.resetCommand(args, "shresetfishingtracker")
    }

    fun isEnabled() = LorenzUtils.inSkyBlock && config.enabled
}
