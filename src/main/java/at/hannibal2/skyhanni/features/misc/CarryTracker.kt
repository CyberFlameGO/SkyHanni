package at.hannibal2.skyhanni.features.misc

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.events.GuiRenderEvent
import at.hannibal2.skyhanni.events.LorenzChatEvent
import at.hannibal2.skyhanni.events.entity.slayer.SlayerDeathEvent
import at.hannibal2.skyhanni.features.gui.customscoreboard.CustomScoreboardUtils.formatNum
import at.hannibal2.skyhanni.features.slayer.SlayerType
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.CollectionUtils.addString
import at.hannibal2.skyhanni.utils.HypixelCommands
import at.hannibal2.skyhanni.utils.LorenzUtils
import at.hannibal2.skyhanni.utils.NumberUtil.formatDouble
import at.hannibal2.skyhanni.utils.NumberUtil.formatDoubleOrUserError
import at.hannibal2.skyhanni.utils.NumberUtil.formatIntOrUserError
import at.hannibal2.skyhanni.utils.RegexUtils.matchMatcher
import at.hannibal2.skyhanni.utils.RenderUtils.renderRenderables
import at.hannibal2.skyhanni.utils.StringUtils.cleanPlayerName
import at.hannibal2.skyhanni.utils.renderables.Renderable
import at.hannibal2.skyhanni.utils.repopatterns.RepoPattern
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import kotlin.time.Duration.Companion.seconds

@SkyHanniModule
object CarryTracker {
    private val config get() = SkyHanniMod.feature.misc

    val customers = mutableListOf<Customer>()
    val carryTypes = mutableMapOf<String, CarryType>()

    var display = listOf<Renderable>()


    private val patternGroup = RepoPattern.group("carry")

    /**
     * REGEX-TEST:
     * §6Trade completed with §r§b[MVP§r§c+§r§b] ClachersHD§r§f§r§6!
     */
    private val tradeCompletedPattern by patternGroup.pattern(
        "trade.completed",
        "§6Trade completed with (?<name>.*)§r§6!",
    )

    /**
     * REGEX-TEST:
     *  §r§a§l+ §r§6500k coins
     */
    private val rawNamePattern by patternGroup.pattern(
        "trade.coins.gained",
        " §r§a§l\\+ §r§6(?<coins>.*) coins",
    )

    @HandleEvent
    fun onSlayerDeath(event: SlayerDeathEvent) {
        val slayerType = event.slayerType
        val tier = event.tier
        val owner = event.owner
        for (customer in customers) {
            if (!customer.name.equals(owner, ignoreCase = true)) continue
            for (carry in customer.carries) {
                val type = carry.type as? SlayerCarryType ?: return
                if (type.slayerType != slayerType) continue
                if (type.tier != tier) continue
                carry.done++
                if (carry.done == carry.requested) {
                    ChatUtils.chat("Carry done for ${customer.name}!")
                    LorenzUtils.sendTitle("§eCarry done!", 3.seconds)
                }
                update()
            }
        }
    }

    // TODO move into own class
    var lastTradedPlayer = ""

    @SubscribeEvent
    fun onChat(event: LorenzChatEvent) {
        tradeCompletedPattern.matchMatcher(event.message) {
            lastTradedPlayer = group("name").cleanPlayerName()
        }

        rawNamePattern.matchMatcher(event.message) {
            val coinsGained = group("coins").formatDouble()
            getCustomer(lastTradedPlayer).alreadyPaid += coinsGained
            update()
        }
    }

    @SubscribeEvent
    fun onRenderOverlay(event: GuiRenderEvent) {
        if (!LorenzUtils.inSkyBlock) return

        config.carryPosition.renderRenderables(display, posLabel = "Carry Tracker")
    }

    fun onCommand(args: Array<String>) {
        if (args.size < 2 || args.size > 3) {
            ChatUtils.userError("Usage:\n/shcarry <customer name> <type> <amountRequested>\n/shcarry <type> <price>")
            return
        }
        if (args.size == 2) {
            setPrice(args[0], args[1])
            return
        }

        val customerName = args[0]

        val rawType = args[1]
        val carryType = getCarryType(rawType) ?: return

        val amountRequested = args[2].formatIntOrUserError() ?: return

        val newCarry = Carry(carryType, amountRequested)

        for (customer in customers) {
            if (!customer.name.equals(customerName, ignoreCase = true)) continue
            val carries = customer.carries
            for (carry in carries.toList()) {
                if (!newCarry.type.sameType(carry.type)) continue
                val newAmountRequested = carry.requested + amountRequested
                if (newAmountRequested < 1) {
                    ChatUtils.userError("New carry amount requested must be positive!")
                    return
                }
                carries.remove(carry)
                val updatedCarry = Carry(carryType, newAmountRequested)
                updatedCarry.done = carry.done
                carries.add(updatedCarry)
                update()
                ChatUtils.chat("Updated carry: §b$customerName §8x$newAmountRequested ${newCarry.type}")
                return
            }
        }
        if (amountRequested < 1) {
            ChatUtils.userError("Carry amount requested must be positive!")
            return
        }

        val customer = getCustomer(customerName)
        customer.carries.add(newCarry)
        update()
        ChatUtils.chat("Started carry: §b$customerName §8x$amountRequested ${newCarry.type}")
    }

    private fun getCarryType(rawType: String): CarryType? =
        carryTypes.getOrPut(rawType) {
            createCarryType(rawType) ?: run {
                ChatUtils.userError("Unknown carry type: '$rawType'")
                return null
            }
        }


    private fun setPrice(rawType: String, rawPrice: String) {
        val carryType = getCarryType(rawType) ?: return

        val price = rawPrice.formatDoubleOrUserError() ?: return
        carryType.pricePer = price
        update()
        ChatUtils.chat("Set carry price for $carryType §eto §6${price.formatNum()} coins.")
    }

    private fun getCustomer(customerName: String): Customer {
        for (customer in customers) {
            if (customer.name.equals(customerName, ignoreCase = true)) {
                return customer
            }
        }
        val customer = Customer(customerName)
        customers.add(customer)
        return customer
    }

    fun CarryType.sameType(other: CarryType): Boolean = name == other.name && tier == other.tier

    private fun update() {
        val list = mutableListOf<Renderable>()
        if (customers.none { it.carries.isNotEmpty() }) {
            display = emptyList()
            return
        }
        list.addString("§c§lCarries")
        for (customer in customers) {
            if (customer.carries.isEmpty()) continue
            addCustomerName(customer, list)

            val carries = customer.carries
            for (carry in carries) {
                val requested = carry.requested
                val done = carry.done
                val missing = requested - done

                val color = if (done > requested) "§c" else if (done == requested) "§a" else "§e"
                val cost = formatCost(carry.type.pricePer)
                val text = "$color$done§8/$color$requested$cost"
                list.add(
                    Renderable.clickAndHover(
                        Renderable.string("  ${carry.type} $text"),
                        tips = buildList {
                            add("§b${customer.name}' ${carry.type} §cCarry")
                            add("")
                            add("§7Requested: §e$requested")
                            add("§7Done: §e$done")
                            add("§7Missing: §e$missing")
                            add("")
                            if (cost != "") {
                                add("§7Cost per: §e${cost.trim()}")
                                add("")
                            }
                            add("§eClick to remove this carry!")
                        },
                        onClick = {
                            carries.remove(carry)
                            update()
                        },
                    ),
                )
            }
        }
        display = list
    }

    private fun addCustomerName(customer: Customer, list: MutableList<Renderable>) {
        val customerName = customer.name
        val totalCost = customer.carries.sumOf { it.getCost() ?: 0.0 }
        val totalCostFormat = formatCost(totalCost)
        if (totalCostFormat != "") {
            val paidFormat = "§6${customer.alreadyPaid.formatNum()}"
            val diffFormat = formatCost(totalCost - customer.alreadyPaid)
            list.add(
                Renderable.clickAndHover(
                    Renderable.string("§b$customerName $paidFormat§8/${totalCostFormat.trim()}"),
                    tips = listOf(
                        "§7Carries for §b$customerName",
                        "",
                        "§7Total cost: $totalCostFormat",
                        "§7Already paid: $paidFormat",
                        "§7Still missing: $diffFormat",
                        "",
                        "§eClick to send infos in party chat!",
                    ),
                    onClick = {
                        HypixelCommands.partyChat("$customerName Carry: ")
                    },
                ),
            )


        } else {
            list.addString("§b$customerName$totalCostFormat")
        }
    }

    private fun Carry.getCost(): Double? {
        return type.pricePer?.let {
//             min(requested, done) * it
            requested * it
        }?.takeIf { it != 0.0 }
    }

    private fun formatCost(totalCost: Double?): String = if (totalCost == 0.0 || totalCost == null) "" else " §6${totalCost.formatNum()}"

    fun createCarryType(input: String): CarryType? {
        if (input.length == 1) return null
        val rawName = input.dropLast(1)
        val tier = input.last().digitToIntOrNull() ?: return null

        getSlayerType(rawName)?.let {
            return SlayerCarryType(it, tier)
        }

        return null
    }

    fun getSlayerType(name: String): SlayerType? =
        when (name.lowercase()) {
            "rev", "revenant", "zombie" -> SlayerType.REVENANT
            "tara", "tarantula", "spider", "brood", "broodmother" -> SlayerType.TARANTULA
            "sven", "wolf", "packmaster" -> SlayerType.SVEN
            "voidling", "void", "voidgloom", "eman", "enderman" -> SlayerType.VOID
            "inferno", "demon", "demonlord", "blaze" -> SlayerType.INFERNO
            "blood", "bloodfiend", "vamp", "vampire", "riftstalker" -> SlayerType.VAMPIRE

            else -> null
        }


    class Customer(
        val name: String,
        var alreadyPaid: Double = 0.0,
        val carries: MutableList<Carry> = mutableListOf(),
    )

    class Carry(val type: CarryType, val requested: Int, var done: Int = 0)


    abstract class CarryType(val name: String, val tier: Int, var pricePer: Double? = null) {
        override fun toString(): String = "§d$name $tier"
    }

    class SlayerCarryType(val slayerType: SlayerType, tier: Int) : CarryType(slayerType.displayName, tier)
//     class DungeonCarryType(val floor: DungeonFloor, masterMode: Boolean) : CarryType(floor.name, tier)
}
