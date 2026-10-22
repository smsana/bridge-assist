package dev.bridgeassist;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import java.util.function.DoubleConsumer;

public final class BridgeSettingsScreen extends Screen {
    private boolean binding, angles;
    private ButtonWidget keyButton;
    public BridgeSettingsScreen() {super(Text.translatable("screen.bridgeassist.title"));}
    private static Text label(String key,boolean v) {
        return Text.translatable(key,Text.translatable(v?"bridgeassist.on":"bridgeassist.off"));
    }
    private static Text duration(BridgeConfig c) {
        return Text.translatable("option.bridgeassist.duration",(int)c.pauseMinMs,(int)c.pauseMaxMs);
    }
    private static Text stance(BridgeConfig c) {
        return Text.translatable("option.bridgeassist.stance",Text.translatable("stance.bridgeassist."+c.stanceMode.toLowerCase(java.util.Locale.ROOT)));
    }
    private static Text buildMode(BridgeConfig c) {
        return Text.translatable("option.bridgeassist.build_mode",Text.translatable("mode.bridgeassist."+c.buildMode.toLowerCase(java.util.Locale.ROOT)));
    }
    @Override protected void init() {
        BridgeConfig c=BridgeAssistClient.config();
        int w=Math.min(620,width-24),x=(width-w)/2,h=(w-6)/2,y=58,r=22,right=x+h+6;
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.bridgeassist.general"),b->{angles=false;rebuildControls();}).dimensions(x,32,h,20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.bridgeassist.angles"),b->{angles=true;rebuildControls();}).dimensions(right,32,h,20).build());
        if(angles) {
            addDrawableChild(new ValueSlider(x,y,w,"option.bridgeassist.straight_pitch",77,83,c.straightPitch,.1,v->c.straightPitch=v));
            addDrawableChild(new ValueSlider(x,y+r,w,"option.bridgeassist.diagonal_pitch",74,82,c.diagonalPitch,.1,v->c.diagonalPitch=v));
            addDrawableChild(new ValueSlider(x,y+2*r,w,"option.bridgeassist.staircase_pitch",77,89,c.staircasePitch,.1,v->c.staircasePitch=v));
            addDrawableChild(new ValueSlider(x,y+3*r,w,"option.bridgeassist.tower_pitch",83,90,c.towerPitch,.1,v->c.towerPitch=v));
        } else {
        addDrawableChild(ButtonWidget.builder(label("option.bridgeassist.human",c.humanize),b->{
            c.humanize=!c.humanize;b.setMessage(label("option.bridgeassist.human",c.humanize));
        }).dimensions(x,y,h,20).build());
        addDrawableChild(ButtonWidget.builder(label("option.bridgeassist.pauses",c.randomPauses),b->{
            c.randomPauses=!c.randomPauses;b.setMessage(label("option.bridgeassist.pauses",c.randomPauses));
        }).dimensions(right,y,h,20).build());
        addDrawableChild(new ValueSlider(x,y+r,h,"option.bridgeassist.strength",0,100,c.humanStrength*100,1,v->c.humanStrength=v/100));
        addDrawableChild(new ValueSlider(right,y+r,h,"option.bridgeassist.interval",1,10,c.pauseIntervalSeconds,1,v->c.pauseIntervalSeconds=v));
        addDrawableChild(ButtonWidget.builder(duration(c),b->{
            if(c.pauseMinMs<150) {c.pauseMinMs=200;c.pauseMaxMs=500;}
            else if(c.pauseMinMs<250) {c.pauseMinMs=300;c.pauseMaxMs=700;}
            else {c.pauseMinMs=100;c.pauseMaxMs=250;}
            b.setMessage(duration(c));
        }).dimensions(x,y+2*r,h,20).build());
        addDrawableChild(ButtonWidget.builder(label("option.bridgeassist.adaptive",c.adaptivePacing),b->{
            c.adaptivePacing=!c.adaptivePacing;b.setMessage(label("option.bridgeassist.adaptive",c.adaptivePacing));
        }).dimensions(right,y+2*r,h,20).build());
        addDrawableChild(new ValueSlider(x,y+3*r,w,"option.bridgeassist.minimum",150,600,c.minPlaceIntervalMs,25,v->c.minPlaceIntervalMs=(int)v));
        addDrawableChild(new ValueSlider(x,y+4*r,h,"option.bridgeassist.aim_settle",1,6,c.aimSettleTicks,1,v->c.aimSettleTicks=(int)v));
        addDrawableChild(new ValueSlider(right,y+4*r,h,"option.bridgeassist.face_edge_margin",0.05,0.30,c.faceEdgeMargin,0.01,v->c.faceEdgeMargin=v));
        addDrawableChild(ButtonWidget.builder(stance(c),b->{
            var modes=SpeedbridgeLogic.Stance.values();c.stanceMode=modes[(SpeedbridgeLogic.stance(c.stanceMode).ordinal()+1)%modes.length].name();
            b.setMessage(stance(c));
        }).dimensions(x,y+5*r,h,20).build());
        addDrawableChild(ButtonWidget.builder(buildMode(c),b->{
            var modes=VerticalCycle.Mode.values();c.buildMode=modes[(VerticalCycle.mode(c.buildMode).ordinal()+1)%modes.length].name();
            b.setMessage(buildMode(c));
        }).tooltip(Tooltip.of(Text.translatable("option.bridgeassist.build_mode_restart"))).dimensions(right,y+5*r,h,20).build());
        addDrawableChild(ButtonWidget.builder(label("option.bridgeassist.auto",c.autoBuild),b->{
            BridgeAssistClient.setAutoBuild(!c.autoBuild);b.setMessage(label("option.bridgeassist.auto",c.autoBuild));
        }).dimensions(x,y+6*r,h,20).build());
        keyButton=addDrawableChild(ButtonWidget.builder(Text.translatable("option.bridgeassist.auto_key",BridgeAssistClient.autoKeyLabel()),b->{
            binding=true;b.setMessage(Text.translatable("option.bridgeassist.press_key"));
        }).dimensions(right,y+6*r,h,20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"),b->close()).dimensions(x,height-26,w,20).build());
    }
    private void rebuildControls() {binding=false;clearChildren();init();}
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(binding) {
            binding=false;
            if(key!=GLFW.GLFW_KEY_ESCAPE)BridgeAssistClient.bindAutoKey(key,scan);
            keyButton.setMessage(Text.translatable("option.bridgeassist.auto_key",BridgeAssistClient.autoKeyLabel()));return true;
        }
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void render(DrawContext ctx,int x,int y,float dt) {
        renderBackground(ctx,x,y,dt);super.render(ctx,x,y,dt);
        ctx.drawCenteredTextWithShadow(textRenderer,title,width/2,12,0xFFFFFF);
        if(height>=240)ctx.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.bridgeassist.hint"),width/2,height-47,0xBBBBBB);
    }
    @Override public boolean shouldPause() {return false;}
    @Override public void close() {BridgeAssistClient.config().save();BridgeAssistClient.settingsChanged();client.setScreen(null);}
    @Override public void removed() {BridgeAssistClient.config().save();BridgeAssistClient.settingsChanged();}
    private static final class ValueSlider extends SliderWidget {
        private final String key;private final double min,max,step;private final DoubleConsumer update;
        ValueSlider(int x,int y,int w,String key,double min,double max,double initial,double step,DoubleConsumer update) {
            super(x,y,w,20,Text.empty(),(initial-min)/(max-min));this.key=key;this.min=min;this.max=max;this.step=step;this.update=update;updateMessage();
        }
        private double number() {return min+Math.round(value*(max-min)/step)*step;}
        @Override protected void updateMessage() {setMessage(Text.translatable(key,step<1?String.format(java.util.Locale.ROOT,"%.1f",number()):Integer.toString((int)number())));}
        @Override protected void applyValue() {update.accept(number());}
    }
}
