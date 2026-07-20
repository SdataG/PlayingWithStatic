package com.SdataG.playingwithstatic.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Only survives directly on top of a placed copper lightning rod -- per ROADMAP.md Phase 3, this block
 * wraps around a rod, it isn't a standalone generator. Placement fails (and an existing one pops off if
 * the rod underneath it is later removed) anywhere else.
 */
public class StaticCollectorBlock extends ShapedBlock {

    public StaticCollectorBlock(Properties properties, VoxelShape shape) {
        super(properties, shape);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).is(Blocks.LIGHTNING_ROD);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.DOWN && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}
