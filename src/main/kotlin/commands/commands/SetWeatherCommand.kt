package com.aznos.commands.commands

import com.aznos.Bullet
import com.aznos.commands.CommandCodes
import com.aznos.entity.player.Player
import com.aznos.packets.play.out.ServerChangeGameStatePacket
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.util.*

class SetWeatherCommand {
    fun register(dispatcher: CommandDispatcher<Player>) {
        dispatcher.register(
            LiteralArgumentBuilder.literal<Player>("setweather")
                .then(
                    RequiredArgumentBuilder.argument<Player, String>("weather", StringArgumentType.word())
                        .executes{ context ->
                            val message = StringArgumentType.getString(context, "weather")
                            when(message.lowercase(Locale.getDefault())) {
                                "clear" -> {
                                    context.source.world?.weather = 0

                                    for(player in Bullet.players) {
                                        player.sendPacket(ServerChangeGameStatePacket(1, 0f))
                                    }
                                }

                                "rain" -> {
                                    context.source.world?.weather = 1

                                    for(player in Bullet.players) {
                                        player.sendPacket(ServerChangeGameStatePacket(2, 0f))
                                    }
                                }

                                else -> {
                                    context.source.sendMessage(Component.text(
                                        "Invalid weather type",
                                        NamedTextColor.RED
                                    ))

                                    return@executes CommandCodes.ILLEGAL_ARGUMENT.id
                                }
                            }

                            context.source.sendMessage(Component.text()
                                .append(Component.text("Weather set to ", NamedTextColor.YELLOW))
                                .append(Component.text(message, NamedTextColor.GREEN))
                                .build()
                            )

                            CommandCodes.SUCCESS.id
                        }
                )
        )
    }
}