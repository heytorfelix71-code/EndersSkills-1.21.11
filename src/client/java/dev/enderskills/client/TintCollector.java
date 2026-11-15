package dev.enderskills.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Passes a player's normal rendering through (so animation mods like EMF / Fresh Animations / Smooth Player Animations
 * still move the model) but recolors every model part with one see-through color; names, shadows, items are left out.
 * Used for the Shadow clone, the Speed Boost afterimages, the Hasten vibration and the red look of Bleed.
 */
public final class TintCollector implements SubmitNodeCollector {
	private static final Pattern LOCATION = Pattern.compile("location=([a-z0-9_.\\-]+:[a-z0-9_./\\-]+)");
	private static final Map<RenderType, RenderType> TRANSLUCENT = new HashMap<>();

	private final OrderedSubmitNodeCollector out;
	private final SubmitNodeCollector root;
	private final int argb;
	private final Identifier texture;

	TintCollector(SubmitNodeCollector root, int argb) {
		this(root, root, argb, null);
	}

	/** With a texture of its own instead of the model's (the cosmic Grasp creature). */
	public TintCollector(SubmitNodeCollector root, int argb, Identifier texture) {
		this(root, root, argb, texture);
	}

	private TintCollector(SubmitNodeCollector root, OrderedSubmitNodeCollector out, int argb, Identifier texture) {
		this.root = root;
		this.out = out;
		this.argb = argb;
		this.texture = texture;
	}

	private RenderType type(RenderType type) {
		return texture != null ? RenderTypes.entityTranslucentEmissive(texture) : translucent(type);
	}

	/** The same texture, drawn see-through. */
	private static RenderType translucent(RenderType type) {
		return TRANSLUCENT.computeIfAbsent(type, t -> {
			Matcher m = LOCATION.matcher(t.toString());
			if (m.find()) {
				Identifier id = Identifier.tryParse(m.group(1));
				if (id != null) {
					return RenderTypes.entityTranslucent(id);
				}
			}
			return t;
		});
	}

	@Override
	public OrderedSubmitNodeCollector order(int order) {
		return new TintCollector(root, root.order(order), argb, texture);
	}

	@Override
	public void submitShadow(PoseStack pose, float radius, List<EntityRenderState.ShadowPiece> pieces) {
	}

	@Override
	public void submitNameTag(PoseStack pose, Vec3 at, int y, Component name, boolean seeThrough, int light, double distance,
			CameraRenderState camera) {
	}

	@Override
	public void submitText(PoseStack pose, float x, float y, FormattedCharSequence text, boolean shadow, Font.DisplayMode mode, int light,
			int color, int background, int outline) {
	}

	@Override
	public void submitFlame(PoseStack pose, EntityRenderState state, Quaternionf rotation) {
	}

	@Override
	public void submitLeash(PoseStack pose, EntityRenderState.LeashState leash) {
	}

	@Override
	public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType type, int light, int overlay, int color,
			TextureAtlasSprite sprite, int outline, ModelFeatureRenderer.CrumblingOverlay crumbling) {
		out.submitModel(model, state, pose, type(type), light, overlay, argb, sprite, 0, null);
	}

	@Override
	public void submitModelPart(ModelPart part, PoseStack pose, RenderType type, int light, int overlay, TextureAtlasSprite sprite,
			boolean sheeted, boolean foil, int color, ModelFeatureRenderer.CrumblingOverlay crumbling, int outline) {
		out.submitModelPart(part, pose, type(type), light, overlay, sprite, sheeted, false, argb, null, 0);
	}

	@Override
	public void submitBlock(PoseStack pose, BlockState state, int light, int overlay, int outline) {
	}

	@Override
	public void submitMovingBlock(PoseStack pose, MovingBlockRenderState state) {
	}

	@Override
	public void submitBlockModel(PoseStack pose, RenderType type, BlockStateModel model, float r, float g, float b, int light, int overlay,
			int outline) {
	}

	@Override
	public void submitItem(PoseStack pose, ItemDisplayContext context, int light, int overlay, int outline, int[] tints, List<BakedQuad> quads,
			RenderType type, ItemStackRenderState.FoilType foil) {
	}

	@Override
	public void submitCustomGeometry(PoseStack pose, RenderType type, CustomGeometryRenderer renderer) {
		int a = argb >>> 24;
		out.submitCustomGeometry(pose, type(type), (p, vc) -> renderer.render(p,
				new GradientConsumer(vc, argb, -1.0E9F, 1.0F, a / 255F, a / 255F)));
	}

	@Override
	public void submitParticleGroup(ParticleGroupRenderer renderer) {
	}
}
