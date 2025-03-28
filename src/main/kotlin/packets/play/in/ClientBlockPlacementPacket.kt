package com.aznos.packets.play.`in`

import com.aznos.datatypes.BlockPositionType
import com.aznos.datatypes.VarInt.readVarInt
import com.aznos.packets.Packet

/**
 * Packet sent by the client when placing a block
 *
 * @property hand The hand used to place the block (0 for main hand, 1 for off-hand)
 * @property location The location of the block being placed
 * @property face The face of the block being placed (0-5)
 * @property cursorPositionX The X position of the crosshair on the block, 0-1
 * @property cursorPositionY The Y position of the crosshair on the block, 0-1
 * @property cursorPositionZ The Z position of the crosshair on the block, 0-1
 * @property insideBlock Whether the players head is inside a block
 */
class ClientBlockPlacementPacket(data: ByteArray) : Packet(data) {
    val hand: Int
    val blockPos: BlockPositionType.BlockPosition
    val face: Int
    val cursorPositionX: Float
    val cursorPositionY: Float
    val cursorPositionZ: Float
    val insideBlock: Boolean

    init {
        val input = getIStream()
        hand = input.readVarInt()

        blockPos = BlockPositionType.BlockPosition(input.readLong())
        face = input.readVarInt()
        cursorPositionX = input.readFloat()
        cursorPositionY = input.readFloat()
        cursorPositionZ = input.readFloat()
        insideBlock = input.readBoolean()
    }
}