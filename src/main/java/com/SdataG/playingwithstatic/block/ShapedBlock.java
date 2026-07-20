package com.SdataG.playingwithstatic.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A block whose selection outline and collision box are a fixed shape approximating its actual
 * (non-full-cube) model, instead of inheriting the default full 16x16x16 box every plain {@link Block}
 * gets. {@code getCollisionShape}'s default implementation already delegates to {@code getShape}, so
 * overriding just this one method fixes both the outline you see and what you actually collide with.
 */
public class ShapedBlock extends Block {

    private final VoxelShape shape;

    public ShapedBlock(Properties properties, VoxelShape shape) {
        super(properties);
        this.shape = shape;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape;
    }
}
