package com.aznos.entity.player

import com.aznos.Bullet
import com.aznos.ClientSession
import com.aznos.entity.Entity
import com.aznos.entity.player.data.ChatPosition
import com.aznos.entity.player.data.GameMode
import com.aznos.entity.player.data.Location
import com.aznos.packets.Packet
import com.aznos.entity.player.data.PlayerProperty
import com.aznos.packets.data.BossBarColor
import com.aznos.packets.data.BossBarDividers
import com.aznos.packets.play.out.ServerBossBarPacket
import com.aznos.packets.play.out.ServerChangeGameStatePacket
import com.aznos.packets.play.out.ServerChatMessagePacket
import com.aznos.packets.play.out.ServerHeldItemChangePacket
import com.aznos.packets.play.out.ServerTimeUpdatePacket
import com.aznos.world.blocks.Block
import com.aznos.world.World
import net.kyori.adventure.text.TextComponent
import java.util.UUID
import kotlin.experimental.or

/**
 * Represents a player in the game
 *
 * @property clientSession The client session associated with the player
 */
class Player(
    val clientSession: ClientSession
) : Entity() {
    val bossBars = mutableListOf<UUID>()
    val loadedChunks: MutableSet<Pair<Int, Int>> = mutableSetOf()

    lateinit var username: String
    lateinit var uuid: UUID
    lateinit var location: Location
    lateinit var locale: String
    lateinit var brand: String

    var inventory: MutableMap<Int, Int> = mutableMapOf()
    var selectedSlot: Int = 0

    var properties: MutableList<PlayerProperty> = mutableListOf()
    var gameMode: GameMode = GameMode.CREATIVE
        private set
    var onGround: Boolean = true
    var viewDistance: Int = 0
    var isSneaking: Boolean = false
    var ping: Int = 0
    var chunkX: Int = 0
    var chunkZ: Int = 0
    var world: World? = Bullet.world

    var health: Int = 20
    var foodLevel: Int = 20
    var saturation: Float = 5f
    var exhaustion: Float = 0f
    var lastSprintLocation: Location? = null

    /**
     * Sends a packet to the players client session
     *
     * @param packet The packet to be sent
     */
    fun sendPacket(packet: Packet) {
        clientSession.sendPacket(packet)
    }

    /**
     * Disconnects the player from the server
     *
     * @param message The message to be shown on why they were disconnected
     */
    fun disconnect(message: String) {
        clientSession.disconnect(message)
    }

    /**
     * Sends a message to the player
     *
     * @param message The message to be sent to the client
     */
    fun sendMessage(message: TextComponent) {
        sendPacket(ServerChatMessagePacket(message, ChatPosition.CHAT, null))
    }

    /**
     * Sets the time of day (clientside) for the player
     *
     * @param time The time to set it to
     */
    fun setTimeOfDay(time: Long) {
        world?.timeOfDay = time
        sendPacket(ServerTimeUpdatePacket(Bullet.world.worldAge, time))
    }

    /**
     * Sets the game mode for the player
     *
     * @param mode The game mode to set
     */
    fun setGameMode(mode: GameMode) {
        gameMode = mode
        sendPacket(ServerChangeGameStatePacket(3, mode.id.toFloat()))
    }

    /**
     * Returns the players held item
     *
     * @return The item ID
     */
    fun getHeldItem(): Int {
        val slotIndex = selectedSlot + 36
        val blockID = inventory[slotIndex]

        return blockID?.let {
            Block.getBlockByID(it)?.id ?: it
        } ?: 0
    }

    /**
     * Sets the players held item slot in the hotbar (0-8)
     *
     * @param slot The slot to select
     */
    fun setHeldSlot(slot: Int) {
        sendPacket(ServerHeldItemChangePacket(slot.toByte()))
    }

    fun addBossBar(title: String, health: Float? = 1f, color: BossBarColor? = BossBarColor.PINK, dividers: BossBarDividers? = BossBarDividers.NONE, darkenSky: Boolean = false, playEndMusic: Boolean = false, createFog: Boolean = false) {
        val uuid = UUID.randomUUID()
        bossBars.add(uuid)

        var flags: Byte = 0
        if(darkenSky) flags = flags or 0x1
        if(playEndMusic) flags = flags or 0x2
        if(createFog) flags = flags or 0x04

        sendPacket(ServerBossBarPacket(
            uuid,
            ServerBossBarPacket.Action.ADD,
            title,
            health,
            color,
            dividers,
            flags
        ))
    }
}