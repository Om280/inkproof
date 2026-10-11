package com.inkproof;

import android.view.*;
import android.graphics.*;
import android.content.Context;
import java.util.*;

/** Active stroke is painted directly by the View, independent of disk/network operations. */
public final class InkView extends View {
    public interface Changed { void onChange(); }
    public final Notebook.Page page;
    public Notebook.Question question;
    public String tool="pen";
    public int color=0xff182433;
    public float width=3;
    public int stabilization=2;
    private final Paint paint=new Paint(3);
    private Notebook.Stroke active;
    private int pointer=-1;
    private Changed changed;
    private final ArrayDeque<Notebook.Stroke> redo=new ArrayDeque<>();
    public InkView(Context c,Notebook.Page p,Notebook.Question q,Changed change){super(c);page=p;question=q;changed=change;setBackgroundColor(q==null?p.background:0xfff5f6f3);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
    private ArrayList<Notebook.Stroke> strokes(){return question==null?page.strokes:question.solution;}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas); for(Notebook.Stroke s:strokes())drawStroke(canvas,s); if(active!=null)drawStroke(canvas,active);}
    private void drawStroke(Canvas c,Notebook.Stroke s){if(s.points.isEmpty())return; paint.setColor(s.color);paint.setAlpha(s.tool.equals("marker")?95:255);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeWidth(s.width*getResources().getDisplayMetrics().density);Path path=new Path();Notebook.Point first=s.points.get(0);path.moveTo(first.x,first.y); for(int i=1;i<s.points.size();i++){Notebook.Point p=s.points.get(i);path.lineTo(p.x,p.y);} if(s.points.size()==1)path.lineTo(first.x+.01f,first.y);c.drawPath(path,paint);}
    @Override public boolean onTouchEvent(android.view.MotionEvent e){int action=e.getActionMasked(); if(action==MotionEvent.ACTION_DOWN){int index=e.getActionIndex(); if(e.getToolType(index)!=MotionEvent.TOOL_TYPE_STYLUS && e.getToolType(index)!=MotionEvent.TOOL_TYPE_ERASER)return false; pointer=e.getPointerId(index); if(tool.equals("eraser")||e.getToolType(index)==MotionEvent.TOOL_TYPE_ERASER){erase(e.getX(index),e.getY(index));return true;} active=new Notebook.Stroke();active.tool=tool;active.color=color;active.width=tool.equals("marker")?width*5:width; add(e,index);return true;}
        if(pointer<0)return false;int index=e.findPointerIndex(pointer);if(index<0)return false;
        if(action==MotionEvent.ACTION_MOVE){if(tool.equals("eraser")){erase(e.getX(index),e.getY(index));return true;}for(int h=0;h<e.getHistorySize();h++)addPoint(e.getHistoricalX(index,h),e.getHistoricalY(index,h),e.getHistoricalPressure(index,h));add(e,index);return true;}
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){if(active!=null){add(e,index);if(active.points.size()>0){strokes().add(active);redo.clear();changed.onChange();}active=null;}pointer=-1;invalidate();return true;}return true;}
    private void add(MotionEvent e,int i){addPoint(e.getX(i),e.getY(i),e.getPressure(i));}
    private void addPoint(float x,float y,float p){if(active==null)return;ArrayList<Notebook.Point> pts=active.points;if(!pts.isEmpty()){Notebook.Point last=pts.get(pts.size()-1);float factor=stabilization==0?1:stabilization==1?.75f:stabilization==2?.55f:.35f;x=last.x+(x-last.x)*factor;y=last.y+(y-last.y)*factor;if(Math.hypot(x-last.x,y-last.y)<.5)return;}pts.add(new Notebook.Point(x,y,p));invalidate((int)x-40,(int)y-40,(int)x+40,(int)y+40);}
    private void erase(float x,float y){ArrayList<Notebook.Stroke> list=strokes();for(int i=list.size()-1;i>=0;i--){Notebook.Stroke s=list.get(i);boolean hit=false;for(Notebook.Point p:s.points)if(Math.hypot(x-p.x,y-p.y)<20+s.width){hit=true;break;}if(hit){list.remove(i);redo.push(s);invalidate();changed.onChange();break;}}}
    public void undo(){ArrayList<Notebook.Stroke> list=strokes();if(!list.isEmpty()){redo.push(list.remove(list.size()-1));invalidate();changed.onChange();}}
    public void redo(){if(!redo.isEmpty()){strokes().add(redo.pop());invalidate();changed.onChange();}}
}
