package com.aznos.packets

import com.aznos.Bullet
import com.aznos.Bullet.breakingBlocks
import com.aznos.Bullet.sprinting
import com.aznos.Bullet.world
import com.aznos.ClientSession
import com.aznos.GameState
import com.aznos.commands.CommandCodes
import com.aznos.commands.CommandManager
import com.aznos.commands.CommandManager.buildCommandGraphFromDispatcher
import com.aznos.datatypes.MetadataType
import com.aznos.datatypes.Slot
import com.aznos.datatypes.VarInt.readVarInt
import com.aznos.entity.player.Player
import com.aznos.entity.player.data.GameMode
import com.aznos.entity.player.data.Location
import com.aznos.entity.player.data.Position
import com.aznos.events.*
import com.aznos.packets.data.ServerStatusResponse
import com.aznos.packets.login.`in`.ClientLoginStartPacket
import com.aznos.packets.login.out.ServerLoginSuccessPacket
import com.aznos.packets.play.`in`.*
import com.aznos.packets.play.`in`.movement.*
import com.aznos.packets.play.out.*
import com.aznos.packets.play.out.movement.*
import com.aznos.util.DurationFormat
import com.aznos.packets.status.`in`.ClientStatusPingPacket
import com.aznos.packets.status.`in`.ClientStatusRequestPacket
import com.aznos.packets.status.out.ServerStatusPongPacket
import com.aznos.world.blocks.Block
import com.aznos.world.data.BlockStatus
import com.mojang.brigadier.exceptions.CommandSyntaxException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import packets.handshake.HandshakePacket
import packets.status.out.ServerStatusResponsePacket
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.*
import kotlin.experimental.and
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.Duration

/**
 * Handles all incoming packets by dispatching them to the appropriate handler methods
 *
 * @property client The clients session
 */
@Suppress("UnusedParameter", "TooManyFunctions", "LargeClass")
class PacketHandler(
    private val client: ClientSession
) {
    @PacketReceiver
    fun onPlayerAbilities(packet: ClientPlayerAbilitiesPacket) {
        if(client.player.canFly) {
            val flying = (packet.flags and 0x02).toInt() == 0x02
            client.player.isFlying = flying
        } else {
            client.player.isFlying = false
            client.player.sendPacket(ServerPlayerAbilitiesPacket(
                0,
                0f,
            ))
        }
    }

    @PacketReceiver
    fun onTabComplete(packet: ClientTabCompletePacket) {
        val dispatcher = CommandManager.dispatcher
        val rawInput = packet.text
        val input = if (rawInput.startsWith("/")) rawInput.substring(1) else rawInput

        val parseResults = dispatcher.parse(input, client.player)
        dispatcher.getCompletionSuggestions(parseResults, input.length).thenAccept { suggestions ->
            val lastSpace = input.lastIndexOf(' ')
            val start = lastSpace + 1
            val length = input.length - start

            val startStr = input.substring(start)

            val matches = suggestions.list
                .filter { it.text.startsWith(startStr, ignoreCase = true) }
                .map { it.text }

            val formattedMatches = matches.map { match ->
                if (lastSpace == -1) "/$match" else match
            }

            client.player.sendPacket(ServerTabCompletePacket(
                packet.transactionID,
                start = start + 1,
                length = length,
                matches = formattedMatches
            ))
        }
    }

    @PacketReceiver
    fun onClientStatus(packet: ClientStatusPacket) {
        when(packet.actionID) {
            0 -> { // Perform respawn
                client.player.sendPacket(ServerRespawnPacket(
                    Bullet.dimensionCodec!!,
                    "minecraft:overworld",
                    GameMode.SURVIVAL,
                    false,
                    false,
                    true
                ))

                client.player.status.health = 20
                client.player.status.foodLevel = 20
                client.player.status.saturation = 5.0f
                client.player.status.exhaustion = 0f

                client.player.sendPacket(
                    ServerPlayerPositionAndLookPacket(
                        Location(8.5, 2.0, 8.5, 0f, 0f)
                    )
                )
            }

            1 -> { // Request statistics

            }
        }
    }

    @PacketReceiver
    fun onEntityInteract(packet: ClientInteractEntityPacket) {
        val attacker = client.player

        val event = PlayerInteractEntityEvent(attacker, packet.entityID, packet.type)
        EventManager.fire(event)
        if(event.isCancelled) return

        if(packet.type == 1) {
            for(player in Bullet.players) {
                if(player.entityID == packet.entityID && player.gameMode == GameMode.SURVIVAL) {
                    player.status.health -= 1

                    player.sendPacket(ServerUpdateHealthPacket(
                        player.status.health.toFloat(),
                        player.status.foodLevel,
                        player.status.saturation
                    ))

                    player.sendPacket(ServerAnimationPacket(
                        player.entityID,
                        1
                    ))

                    player.status.exhaustion += 0.1f

                    val dx = player.location.x - attacker.location.x
                    val dy = player.location.y - attacker.location.y
                    val dz = player.location.z - attacker.location.z
                    val distance = sqrt(dx * dx + dy * dy + dz * dz)
                    if(distance != 0.0) {
                        val kbStrength = 0.5

                        val kbX = (dx / distance) * kbStrength
                        val kbY = if(player.onGround) 0.3 else 0.125
                        val kbZ = (dz / distance) * kbStrength

                        player.sendPacket(ServerEntityVelocityPacket(
                            player.entityID,
                            (kbX * 8000).toInt().toShort(),
                            (kbY * 8000).toInt().toShort(),
                            (kbZ * 8000).toInt().toShort()
                        ))
                    }
                }
            }
        }
    }

    @PacketReceiver
    fun onHeldItemChange(packet: ClientHeldItemChangePacket) {
        val event = PlayerHeldItemChangeEvent(client.player, packet.slot.toInt())
        EventManager.fire(event)
        if(event.isCancelled) return

        client.player.selectedSlot = packet.slot.toInt()
        sendHeldItemUpdate()
    }

    @PacketReceiver
    fun onCreativeInventoryAction(packet: ClientCreativeInventoryActionPacket) {
        if(packet.slot.present) {
            packet.slot.itemID?.let { itemID ->
                client.player.inventory.items[packet.slotIndex.toInt()] = itemID
            }
        } else {
            client.player.inventory.items.remove(packet.slotIndex.toInt())
        }

        if(packet.slotIndex.toInt() == client.player.selectedSlot + 36) {
            sendHeldItemUpdate()
        }
    }

    @PacketReceiver
    fun onPluginMessage(packet: ClientPluginMessagePacket) {
        when(packet.channel) {
            "minecraft:brand" -> {
                val input = DataInputStream(ByteArrayInputStream(packet.pluginData))
                val length = input.readVarInt()

                val brandBytes = ByteArray(length)
                input.readFully(brandBytes)

                val brand = String(brandBytes, Charsets.UTF_8)
                client.player.brand = brand

                val event = PlayerBrandEvent(client.player, brand)
                EventManager.fire(event)
                if(event.isCancelled) {
                    client.player.disconnect(Component.text("Your client brand is not supported"))
                    return
                }
            }
        }
    }

    @PacketReceiver
    fun onPlayerSettingsChange(packet: ClientSettingsPacket) {
        val event = PlayerSettingsChangeEvent(
            client.player,
            packet.locale,
            packet.viewDistance.toInt(),
            packet.chatMode,
            packet.chatColors,
            packet.displayedSkinParts.toInt(),
            packet.mainHand
        )
        EventManager.fire(event)
        if(event.isCancelled) return

        client.player.viewDistance = packet.viewDistance.toInt()
        client.player.locale = packet.locale

        client.sendPacket(ServerUpdateViewPositionPacket(client.player.chunkX, client.player.chunkZ))
        client.updatePlayerChunks(client.player.chunkX, client.player.chunkZ)
    }

    /**
     * Called when a client performs an action, such as jumping, sneaking, or sprinting
     */
    @PacketReceiver
    fun onPlayerAction(packet: ClientEntityActionPacket) {
        when(packet.actionID) {
            0 -> { //Start sneaking
                val event = PlayerSneakEvent(client.player, true)
                EventManager.fire(event)
                if(event.isCancelled) return

                client.player.isSneaking = true
                updateEntityMetadata(client.player, 6, 5)
            }

            1 -> { //Stop sneaking
                val event = PlayerSneakEvent(client.player, false)
                EventManager.fire(event)
                if(event.isCancelled) return

                client.player.isSneaking = false
                updateEntityMetadata(client.player, 6, 0)
            }

            3 -> { //Start sprinting
                val event = PlayerSprintEvent(client.player, true)
                EventManager.fire(event)
                if(event.isCancelled) return

                sprinting.add(client.player.entityID)
                client.player.lastSprintLocation = client.player.location
            }

            4 -> { //Stop sprinting
                val event = PlayerSprintEvent(client.player, false)
                EventManager.fire(event)
                if(event.isCancelled) return

                sprinting.remove(client.player.entityID)
                client.player.lastSprintLocation = null
            }
        }
    }

    /**
     * Called when a client starts digging a block
     */
    @PacketReceiver
    fun onPlayerDig(packet: ClientDiggingPacket) {
        val event = BlockBreakEvent(
            client.player,
            packet.status,
            Position(packet.location.x, packet.location.y, packet.location.z),
            packet.face
        )
        EventManager.fire(event)
        if(event.isCancelled) return

        if(client.player.gameMode == GameMode.CREATIVE && event.status == BlockStatus.STARTED_DIGGING.id) {
            for(otherPlayer in Bullet.players) {
                if(otherPlayer != client.player) {
                    otherPlayer.sendPacket(ServerBlockChangePacket(
                        event.location,
                        0
                    ))
                }
            }

            removeBlock(event.location)
        } else if(client.player.gameMode == GameMode.SURVIVAL) {
            when(event.status) {
                BlockStatus.STARTED_DIGGING.id -> {
                    val breakTime = getStoneBreakTime()
                    startBlockBreak(event.location, breakTime.toInt())
                }

                BlockStatus.CANCELLED_DIGGING.id -> {
                    stopBlockBreak(event.location)
                }

                BlockStatus.FINISHED_DIGGING.id -> {
                    client.player.status.exhaustion += 0.005f
                    stopBlockBreak(event.location)

                    removeBlock(event.location)
                }
            }
        }
    }

    @PacketReceiver
    fun onArmSwing(packet: ClientAnimationPacket) {
        val event = PlayerArmSwingEvent(client.player)
        EventManager.fire(event)
        if(event.isCancelled) return

        for(otherPlayer in Bullet.players) {
            if(otherPlayer != client.player) {
                otherPlayer.sendPacket(ServerAnimationPacket(client.player.entityID, 0))
            }
        }
    }

    /**
     * Called when a client places a block
     */
    @PacketReceiver
    fun onBlockPlacement(packet: ClientBlockPlacementPacket) {
        val event = BlockPlaceEvent(
            client.player,
            packet.hand,
            Position(packet.location.x, packet.location.y, packet.location.z),
            packet.face,
            packet.cursorPositionX,
            packet.cursorPositionY,
            packet.cursorPositionZ,
            packet.insideBlock
        )
        EventManager.fire(event)
        if(event.isCancelled) return

        when(event.face) {
            0 -> event.location.y -= 1
            1 -> event.location.y += 1
            2 -> event.location.z -= 1
            3 -> event.location.z += 1
            4 -> event.location.x -= 1
            5 -> event.location.x += 1
        }

        val heldItem = client.player.getHeldItem()

        val block = Block.getBlockFromID(heldItem) ?: Block.AIR
        val stateBlock = Block.getStateID(block)

        if(Bullet.shouldPersist) world.modifiedBlocks[event.location] = heldItem

        for(otherPlayer in Bullet.players) {
            if(otherPlayer != client.player) {
                otherPlayer.sendPacket(ServerBlockChangePacket(
                    Position(
                        event.location.x,
                        event.location.y,
                        event.location.z
                    ),
                    stateBlock
                ))
            }
        }
    }

    /**
     * Every 20 ticks the client will send an empty movement packet telling the server if the
     * client is on the ground or not
     */
    @PacketReceiver
    fun onPlayerMovement(packet: ClientPlayerMovement) {
        val player = client.player
        player.onGround = packet.onGround

        for(otherPlayer in Bullet.players) {
            if(otherPlayer != player) {
                otherPlayer.clientSession.sendPacket(ServerEntityMovementPacket(player.entityID))
            }
        }
    }

    /**
     * Handles when a player rotates to a new yaw and pitch
     */
    @PacketReceiver
    fun onPlayerRotation(packet: ClientPlayerRotation) {
        val event = PlayerMoveEvent(
            client.player,
            Location(
                client.player.location.x, client.player.location.y, client.player.location.z,
                packet.yaw, packet.pitch
            ),
            Location(
                client.player.location.x, client.player.location.y, client.player.location.z,
                client.player.location.yaw, client.player.location.pitch
            )
        )
        EventManager.fire(event)
        if(event.isCancelled) return

        val player = client.player
        player.location = Location(player.location.x, player.location.y, player.location.z, packet.yaw, packet.pitch)
        player.onGround = packet.onGround

        for(otherPlayer in Bullet.players) {
            if(otherPlayer != player) {
                otherPlayer.clientSession.sendPacket(ServerEntityRotationPacket(
                    player.entityID,
                    player.location.yaw,
                    player.location.pitch,
                    player.onGround
                ))

                otherPlayer.clientSession.sendPacket(
                    ServerEntityHeadLook(
                        player.entityID,
                        player.location.yaw
                    )
                )
            }
        }
    }

    /**
     * Handles when a player moves to a new position and rotation axis at the same time
     */
    @PacketReceiver
    fun onPlayerPositionAndRotation(packet: ClientPlayerPositionAndRotation) {
        val event = PlayerMoveEvent(
            client.player,
            Location(
                packet.x, packet.feetY, packet.z,
                packet.yaw, packet.pitch
            ),
            Location(
                client.player.location.x, client.player.location.y, client.player.location.z,
                client.player.location.yaw, client.player.location.pitch
            )
        )
        EventManager.fire(event)
        if(event.isCancelled) return

        val player = client.player
        val lastLocation = player.location
        val wasOnGround = player.onGround

        val newChunkX = (packet.x / 16).toInt()
        val newChunkZ = (packet.z / 16).toInt()

        if(newChunkX != player.chunkX || newChunkZ != player.chunkZ) {
            player.chunkX = newChunkX
            player.chunkZ = newChunkZ
            client.sendPacket(ServerUpdateViewPositionPacket(
                newChunkX,
                newChunkZ
            ))
            client.updatePlayerChunks(newChunkX, newChunkZ)
        }

        val (deltaX, deltaY, deltaZ) = calculateDeltas(
            packet.x, packet.feetY, packet.z,
            lastLocation.x, lastLocation.y, lastLocation.z
        )

        handleFoodLevel(player, packet.x, packet.z, packet.onGround, wasOnGround)

        player.location = Location(packet.x, packet.feetY, packet.z, packet.yaw, packet.pitch)
        player.onGround = packet.onGround
        checkFallDamage()

        for(otherPlayer in Bullet.players) {
            if(otherPlayer != player) {
                otherPlayer.clientSession.sendPacket(
                    ServerEntityPositionAndRotationPacket(
                        player.entityID,
                        deltaX,
                        deltaY,
                        deltaZ,
                        player.location.yaw,
                        player.location.pitch,
                        player.onGround
                    )
                )

                otherPlayer.clientSession.sendPacket(
                    ServerEntityHeadLook(
                        player.entityID,
                        player.location.yaw
                    )
                )
            }
        }
    }

    /**
     * Handles when a player moves to a new position
     */
    @PacketReceiver
    fun onPlayerPosition(packet: ClientPlayerPositionPacket) {
        val event = PlayerMoveEvent(
            client.player,
            Location(
                packet.x, packet.feetY, packet.z,
                client.player.location.yaw, client.player.location.pitch
            ),
            Location(
                client.player.location.x, client.player.location.y, client.player.location.z,
                client.player.location.yaw, client.player.location.pitch
            )
        )
        EventManager.fire(event)
        if(event.isCancelled) return

        val player = client.player
        val lastLocation = player.location
        val wasOnGround = player.onGround

        val newChunkX = (packet.x / 16).toInt()
        val newChunkZ = (packet.z / 16).toInt()

        if(newChunkX != player.chunkX || newChunkZ != player.chunkZ) {
            player.chunkX = newChunkX
            player.chunkZ = newChunkZ
            client.sendPacket(ServerUpdateViewPositionPacket(
                newChunkX,
                newChunkZ
            ))
            client.updatePlayerChunks(newChunkX, newChunkZ)
        }

        val (deltaX, deltaY, deltaZ) = calculateDeltas(
            packet.x, packet.feetY, packet.z,
            lastLocation.x, lastLocation.y, lastLocation.z
        )

        handleFoodLevel(player, packet.x, packet.z, packet.onGround, wasOnGround)

        player.location = Location(packet.x, packet.feetY, packet.z, player.location.yaw, player.location.pitch)
        player.onGround = packet.onGround
        checkFallDamage()

        for(otherPlayer in Bullet.players) {
            if(otherPlayer != player) {
                otherPlayer.clientSession.sendPacket(
                    ServerEntityPositionPacket(
                        player.entityID,
                        deltaX,
                        deltaY,
                        deltaZ,
                        player.onGround
                    )
                )
            }
        }
    }

    /**
     * Handles when a chat message is received
     */
    @PacketReceiver
    fun onChatMessage(packet: ClientChatMessagePacket) {
        val message = packet.message

        if(message.length > 255) {
            client.player.sendMessage(
                Component.text("Message is too long")
                .color(NamedTextColor.RED)
            )

            return
        }

        if(message.startsWith('/') && message.length > 1) {
            val command = message.substring(1)
            val commandSource = client.player

            @Suppress("TooGenericExceptionCaught")
            val result: Int = try {
                CommandManager.dispatcher.execute(command, commandSource)
            } catch (e: CommandSyntaxException){
                CommandCodes.ILLEGAL_SYNTAX.id
            } catch (e: Exception) {
                Bullet.logger.warn("Error running command `$message`:", e)
                return
            }

            if(result == CommandCodes.SUCCESS.id) return

            when(result) {
                CommandCodes.UNKNOWN.id ->
                    commandSource.sendMessage(Component.text("Unknown command")
                        .color(NamedTextColor.RED))

                CommandCodes.ILLEGAL_ARGUMENT.id,
                CommandCodes.ILLEGAL_SYNTAX.id ->
                    commandSource.sendMessage(Component.text("Invalid command syntax, try typing /help")
                        .color(NamedTextColor.RED))

                CommandCodes.INVALID_PERMISSIONS.id ->
                    commandSource.sendMessage(Component.text("You don't have permission to use this command")
                        .color(NamedTextColor.RED))
            }
            return
        }

        val formattedMessage = message.replace('&', '§')

        val event = PlayerChatEvent(client.player, formattedMessage)
        EventManager.fire(event)
        if(event.isCancelled) return

        val textComponent = Component.text()
            .append(Component.text().content("<").color(NamedTextColor.GRAY))
            .append(Component.text().content(client.player.username).color(TextColor.color(0x55FFFF)))
            .append(Component.text().content("> ").color(NamedTextColor.GRAY))
            .append(MiniMessage.miniMessage().deserialize(formattedMessage))
            .build()

        Bullet.broadcast(textComponent)
    }

    /**
     * Handles when the client responds to the server keep alive packet to tell the server the client is still online
     * It also calculates the round trip time (RTT) and updates the players ping
     */
    @PacketReceiver
    fun onKeepAlive(packet: ClientKeepAlivePacket) {
        val event = PlayerHeartbeatEvent(client.player)
        EventManager.fire(event)
        if(event.isCancelled) return

        client.respondedToKeepAlive = true

        val receivedTimestamp = packet.keepAliveID
        val currentTime = System.currentTimeMillis()
        val rtt = (currentTime - receivedTimestamp).toInt()

        client.player.ping = rtt / 2

        for(player in Bullet.players) {
            player.sendPacket(ServerPlayerInfoPacket(
                2,
                client.player
            ))
        }
    }

    /**
     * Handles when the client tells the server it's ready to log in
     *
     * The server first checks for a valid version and uuid, then sends a login success packet
     * It'll then transition the game state into play mode
     * and send a join game and player position/look packet to get past all loading screens
     */
    @PacketReceiver
    fun onLoginStart(packet: ClientLoginStartPacket) {
        val preJoinEvent = PlayerPreJoinEvent()
        EventManager.fire(preJoinEvent)
        if(preJoinEvent.isCancelled) return

        val username = packet.username
        val uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:$username").toByteArray())

        checkLoginValidity(username)

        val player = initializePlayer(username, uuid)

        client.sendPacket(ServerLoginSuccessPacket(uuid, username))
        client.state = GameState.PLAY

        if(checkForBan()) return

        client.sendPacket(
            ServerJoinGamePacket(
                player.entityID,
                false,
                player.gameMode,
                "minecraft:overworld",
                Bullet.dimensionCodec!!,
                Bullet.max_players,
                32,
                reducedDebugInfo = false,
                enableRespawnScreen = true,
                isDebug = false,
                isFlat = true
            )
        )

        client.sendPacket(ServerPlayerPositionAndLookPacket(player.location))

        val joinEvent = PlayerJoinEvent(client.player)
        EventManager.fire(joinEvent)
        if(joinEvent.isCancelled) return

        Bullet.players.add(player)
        scheduleTimers()

        client.sendPacket(ServerChunkPacket(0, 0))
        sendSpawnPlayerPackets(player)

        client.sendPacket(ServerUpdateViewPositionPacket(player.chunkX, player.chunkZ))
        client.updatePlayerChunks(player.chunkX, player.chunkZ)

        addPlayerToPersistantData()
        sendBlockChanges()

        world.writePlayerData(
            player.username,
            player.uuid,
            player.location,
            player.status.health,
            player.status.foodLevel,
            player.status.saturation,
            player.status.exhaustion
        )

        player.setTimeOfDay(world.timeOfDay)
        if(world.weather == 1) player.sendPacket(ServerChangeGameStatePacket(2, 0f))
        else player.sendPacket(ServerChangeGameStatePacket(1, 0f))

        val (nodes, rootIndex) = buildCommandGraphFromDispatcher(CommandManager.dispatcher)
        client.sendPacket(ServerDeclareCommandsPacket(nodes, rootIndex))
    }

    /**
     * Handles a ping packet by sending a pong response and closing the connection
     */
    @PacketReceiver
    fun onPing(packet: ClientStatusPingPacket) {
        client.sendPacket(ServerStatusPongPacket(packet.payload))
        client.close()
    }

    /**
     * Handles a status request packet by sending a server status response
     */
    @PacketReceiver
    fun onStatusRequest(packet: ClientStatusRequestPacket) {
        val event = StatusRequestEvent(Bullet.max_players, 0, Bullet.motd)
        EventManager.fire(event)
        if(event.isCancelled) return

        val response = ServerStatusResponse(
            ServerStatusResponse.Version(Bullet.VERSION, Bullet.PROTOCOL),
            ServerStatusResponse.Players(event.maxPlayers, event.onlinePlayers),
            event.motd,
            Bullet.favicon,
            false
        )

        client.sendPacket(ServerStatusResponsePacket(Json.encodeToString(response)))
    }

    /**
     * Handles a handshake packet by updating the client state and protocol
     */
    @PacketReceiver
    fun onHandshake(packet: HandshakePacket) {
        client.state = if(packet.state == 2) GameState.LOGIN else GameState.STATUS
        client.protocol = packet.protocol ?: -1

        val event = HandshakeEvent(client.state, client.protocol)
        EventManager.fire(event)
        if(event.isCancelled) return
    }

    /**
     * Dispatches the given packet to the corresponding handler method based on its type
     *
     * @param packet The packet to handle
     */
    fun handle(packet: Packet) {
        @Suppress("TooGenericExceptionCaught")
        try {
            for(method in javaClass.methods) {
                if(method.isAnnotationPresent(PacketReceiver::class.java)) {
                    val params: Array<Class<*>> = method.parameterTypes
                    if(params.size == 1 && params[0] == packet.javaClass) {
                        method.invoke(this, packet)
                    }
                }
            }
        } catch (e: Exception){
            Bullet.logger.error("Could not handle packet ${packet.javaClass.name}", e)
        }
    }

    private fun calculateDeltas(
        currentX: Double, currentY: Double, currentZ: Double,
        lastX: Double, lastY: Double, lastZ: Double
    ): Triple<Short, Short, Short> {
        val deltaX = ((currentX - lastX) * 4096).toInt().coerceIn(-32768, 32767).toShort()
        val deltaY = ((currentY - lastY) * 4096).toInt().coerceIn(-32768, 32767).toShort()
        val deltaZ = ((currentZ - lastZ) * 4096).toInt().coerceIn(-32768, 32767).toShort()
        return Triple(deltaX, deltaY, deltaZ)
    }

    private fun initializePlayer(username: String, uuid: UUID): Player {
        val player = Player(client)
        player.username = username
        player.uuid = uuid

        for(i in 1..45) {
            player.inventory.items[i] = 0
        }

        player.location = Location(8.5, 2.0, 8.5, 0f, 0f)
        player.onGround = false

        if(player.gameMode != GameMode.SURVIVAL || player.gameMode != GameMode.ADVENTURE) {
            player.canFly = true
        }

        client.player = player
        return player
    }

    private fun sendSpawnPlayerPackets(player: Player) {
        for(otherPlayer in Bullet.players) {
            if(otherPlayer != player) {
                otherPlayer.clientSession.sendPacket(
                    ServerSpawnPlayerPacket(
                        player.entityID,
                        player.uuid,
                        player.location.x,
                        player.location.y,
                        player.location.z,
                        player.location.yaw,
                        player.location.pitch
                    )
                )
            }
        }

        for(existingPlayer in Bullet.players) {
            if(existingPlayer != player) {
                client.sendPacket(
                    ServerSpawnPlayerPacket(
                        existingPlayer.entityID,
                        existingPlayer.uuid,
                        existingPlayer.location.x,
                        existingPlayer.location.y,
                        existingPlayer.location.z,
                        existingPlayer.location.yaw,
                        existingPlayer.location.pitch
                    )
                )
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startBlockBreak(location: Position, breakTime: Int) {
        if(breakingBlocks.containsKey(location)) return

        val job = GlobalScope.launch {
            val stepTime = breakTime.toLong() / 9

            for(stage in 0..9) {
                for(otherPlayer in Bullet.players) {
                    if(otherPlayer != client.player) {
                        otherPlayer.sendPacket(ServerBlockBreakAnimationPacket(client.player.entityID, location, stage))
                    }
                }

                delay(stepTime)
            }

            for(otherPlayer in Bullet.players) {
                if(otherPlayer != client.player) {
                    otherPlayer.sendPacket(ServerBlockChangePacket(location, 0))
                }
            }

            breakingBlocks.remove(location)
        }

        breakingBlocks[location] = job
    }

    private fun stopBlockBreak(location: Position) {
        breakingBlocks[location]?.cancel()
        breakingBlocks.remove(location)

        for(otherPlayer in Bullet.players) {
            if(otherPlayer != client.player) {
                otherPlayer.sendPacket(ServerBlockBreakAnimationPacket(otherPlayer.entityID, location, -1))
            }
        }
    }

    private fun getStoneBreakTime(): Long {
        return ((1.5 * 30) * 140).toLong()
    }

    private fun updateEntityMetadata(player: Player, index: Int, value: Int) {
        val packet = ServerEntityMetadataPacket(
            player.entityID,
            listOf(MetadataType.MetadataEntry(index.toByte(), 18, value))
        )

        for(otherPlayer in Bullet.players) {
            if(otherPlayer != player) {
                otherPlayer.sendPacket(packet)
            }
        }
    }

    private fun sendHeldItemUpdate() {
        val heldItemID = client.player.getHeldItem()

        val heldItemSlot = if(heldItemID == 0) Slot.SlotData(false)
        else Slot.SlotData(true, heldItemID, 1, null)

        for(otherPlayer in Bullet.players) {
            if(otherPlayer != client.player) {
                otherPlayer.sendPacket(ServerEntityEquipmentPacket(
                    client.player.entityID,
                    listOf(0 to heldItemSlot)
                ))
            }
        }
    }

    /**
     * Handles updating the food level when the player moves
     *
     * @param player The player to update
     * @param x The current X position of the player
     * @param z The current Z position of the player
     * @param onGround Whether the player is on the ground
     * @param wasOnGround If the player was on the ground before the movement packet was called
     */
    private fun handleFoodLevel(player: Player, x: Double, z: Double, onGround: Boolean, wasOnGround: Boolean) {
        if(!onGround && wasOnGround) {
            if(sprinting.contains(player.entityID)) {
                player.status.exhaustion += 0.2f
            } else {
                player.status.exhaustion += 0.05f
            }
        }

        if(sprinting.contains(player.entityID)) {
            val distance = sqrt(
                (x - player.lastSprintLocation!!.x).pow(2) +
                    (z - player.lastSprintLocation!!.z).pow(2)
            )

            if(distance >= 1) {
                player.status.exhaustion += 0.1f
                player.lastSprintLocation = player.location
            }
        }
    }

    private fun checkFallDamage() {
        val player = client.player
        if(player.gameMode == GameMode.SURVIVAL) {
            if(player.onGround) {
                if(player.fallDistance > 3) {
                    val damage = ((player.fallDistance - 3).coerceAtLeast(0.0)).toInt()
                    player.status.health -= damage

                    player.sendPacket(ServerUpdateHealthPacket(
                        player.status.health.toFloat(),
                        player.status.foodLevel,
                        player.status.saturation
                    ))
                }

                player.fallDistance = 0.0
                player.lastOnGroundY = player.location.y
            } else {
                if(player.location.y < player.lastOnGroundY) {
                    player.fallDistance += player.lastOnGroundY - player.location.y
                    player.lastOnGroundY = player.location.y
                } else {
                    player.lastOnGroundY = player.location.y
                }
            }
        }
    }

    private fun removeBlock(location: Position) {
        if(Bullet.shouldPersist) {
            if(world.modifiedBlocks.keys.find {
                    it.x == location.x && it.y == location.y && it.z == location.z
                } != null) {
                world.modifiedBlocks.remove(location)
            } else {
                world.modifiedBlocks[location] = 0
            }
        }
    }

    private fun checkLoginValidity(username: String) {
        if(client.protocol > Bullet.PROTOCOL) {
            client.disconnect(Component.text()
                .append(Component.text("Your client is outdated, please downgrade to minecraft version"))
                .append(Component.text(" " + Bullet.VERSION).color(NamedTextColor.GOLD))
                .build()
            )

            return
        } else if(client.protocol < Bullet.PROTOCOL) {
            client.disconnect(Component.text()
                .append(Component.text("Your client is outdated, please upgrade to minecraft version"))
                .append(Component.text(" " + Bullet.VERSION).color(NamedTextColor.GOLD))
                .build()
            )

            return
        }

        if(!username.matches(Regex("^[a-zA-Z0-9]{3,16}$"))) {
            client.disconnect(Component.text("Invalid username"))
            return
        }
    }

    private fun scheduleTimers() {
        client.sendPlayerSpawnPacket()
        client.scheduleKeepAlive()
        client.scheduleHalfSecondUpdate()

        if(Bullet.shouldPersist) client.scheduleSaving()
    }

    private fun addPlayerToPersistantData() {
        val player = client.player

        if(Files.exists(Paths.get("./${world.name}/players/${player.uuid}.json")) && Bullet.shouldPersist) {
            world.readPlayerData(player.uuid).let {
                player.status.health = it.health
                player.status.foodLevel = it.foodLevel
                player.status.saturation = it.saturation
                player.status.exhaustion = it.exhaustionLevel
                player.location = it.location

                player.sendPacket(ServerUpdateHealthPacket(
                    player.status.health.toFloat(),
                    player.status.foodLevel,
                    player.status.saturation
                ))

                player.sendPacket(ServerPlayerPositionAndLookPacket(player.location))
            }
        }
    }

    private fun sendBlockChanges() {
        if(Files.exists(Paths.get("./${world.name}/data/blocks.json")) && Bullet.shouldPersist) {
            world.readBlockData().let {
                for((position, blockID) in it) {
                    val block = Block.getStateID(Block.getBlockFromID(blockID) ?: Block.AIR)
                    client.player.sendPacket(ServerBlockChangePacket(position, block))
                }
            }
        }
    }

    private fun checkForBan(): Boolean {
        if(Bullet.shouldPersist) {
            val bannedPath = Paths.get("./${world.name}/data/banned_players.json")
            if(Files.exists(bannedPath)) {
                val bans = world.readBannedPlayers()
                val ban = bans.find { it.uuid == client.player.uuid }

                if(ban != null) {
                    val banEnd = ban.currentTime + ban.duration.inWholeMilliseconds
                    val now = System.currentTimeMillis()

                    if(ban.duration.inWholeSeconds != 0L && now >= banEnd) {
                        Bullet.world.unbanPlayer(client.player.uuid)
                        return false
                    }

                    val expirationText = if(ban.duration.inWholeMilliseconds == 0L) {
                        "permanently"
                    } else {
                        val expirationMillis = ban.currentTime + ban.duration.inWholeMilliseconds
                        val expirationTime = Instant.ofEpochMilli(expirationMillis)
                            .atZone(ZoneId.systemDefault())

                        val dayOfMonth = expirationTime.dayOfMonth
                        val daySuffix = DurationFormat.getDaySuffix(dayOfMonth)

                        val formattedDate = expirationTime.format(
                            DateTimeFormatter.ofPattern("MMMM d'$daySuffix' yyyy 'at' H:mm")
                        )

                        "Expires $formattedDate"
                    }

                    client.disconnect(
                        Component.text()
                            .append(Component.text("You have been banned!\n", NamedTextColor.RED))
                            .append(Component.text(expirationText, NamedTextColor.RED))
                            .append(Component.text("\n\n", NamedTextColor.RED))
                            .append(Component.text("Reason: ", NamedTextColor.RED))
                            .append(Component.text(ban.reason, NamedTextColor.GRAY))
                            .build()
                    )

                    return true
                }
            }
        }

        return false
    }
}