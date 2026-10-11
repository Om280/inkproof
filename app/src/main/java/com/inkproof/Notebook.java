package com.inkproof;

import android.content.Context;
import org.json.*;
import java.util.*;
import java.io.*;

/** Persistent vector document. IDs are never derived from position. */
public final class Notebook {
    public final ArrayList<Page> pages = new ArrayList<>();
    public static String id() { return UUID.randomUUID().toString(); }
    public static final class Point {
        public float x,y,p;
        Point(float x,float y,float p) { this.x=x; this.y=y; this.p=p; }
        JSONObject json() throws JSONException { JSONObject j=new JSONObject(); j.put("x",x); j.put("y",y); j.put("p",p); return j; }
        static Point read(JSONObject j) { return new Point((float)j.optDouble("x"),(float)j.optDouble("y"),(float)j.optDouble("p",1)); }
    }
    public static final class Stroke {
        public String id=id(), tool="pen";
        public int color=0xff182433;
        public float width=3;
        public final ArrayList<Point> points=new ArrayList<>();
        JSONObject json() throws JSONException { JSONObject j=new JSONObject(); j.put("id",id); j.put("tool",tool); j.put("color",color); j.put("width",width); JSONArray a=new JSONArray(); for(Point p:points)a.put(p.json()); j.put("points",a); return j; }
        static Stroke read(JSONObject j) { Stroke s=new Stroke(); s.id=j.optString("id",id()); s.tool=j.optString("tool","pen"); s.color=j.optInt("color",s.color); s.width=(float)j.optDouble("width",3); JSONArray a=j.optJSONArray("points"); if(a!=null)for(int i=0;i<a.length();i++)s.points.add(Point.read(a.optJSONObject(i))); return s; }
    }
    public static final class Question {
        public String id=id(), prompt="";
        public final ArrayList<Stroke> solution=new ArrayList<>();
        JSONObject json() throws JSONException { JSONObject j=new JSONObject(); j.put("id",id); j.put("prompt",prompt); JSONArray a=new JSONArray(); for(Stroke s:solution)a.put(s.json()); j.put("solution",a); return j; }
        static Question read(JSONObject j) { Question q=new Question(); q.id=j.optString("id",id()); q.prompt=j.optString("prompt",""); JSONArray a=j.optJSONArray("solution"); if(a!=null)for(int i=0;i<a.length();i++)q.solution.add(Stroke.read(a.optJSONObject(i))); return q; }
    }
    public static final class Page {
        public String id=id(), title="Page", template="plain";
        public int background=0xffffffff;
        public final ArrayList<Stroke> strokes=new ArrayList<>();
        public final ArrayList<Question> questions=new ArrayList<>();
        JSONObject json() throws JSONException { JSONObject j=new JSONObject(); j.put("id",id); j.put("title",title); j.put("template",template); j.put("background",background); JSONArray a=new JSONArray(); for(Stroke s:strokes)a.put(s.json()); j.put("strokes",a); a=new JSONArray(); for(Question q:questions)a.put(q.json()); j.put("questions",a); return j; }
        static Page read(JSONObject j) { Page p=new Page(); p.id=j.optString("id",id()); p.title=j.optString("title","Page"); p.template=j.optString("template","plain"); p.background=j.optInt("background",-1); JSONArray a=j.optJSONArray("strokes"); if(a!=null)for(int i=0;i<a.length();i++)p.strokes.add(Stroke.read(a.optJSONObject(i))); a=j.optJSONArray("questions"); if(a!=null)for(int i=0;i<a.length();i++)p.questions.add(Question.read(a.optJSONObject(i))); return p; }
    }
    public Page addPage() { Page p=new Page(); p.title="Page "+(pages.size()+1); pages.add(p); return p; }
    public JSONObject json() throws JSONException { JSONObject j=new JSONObject(); JSONArray a=new JSONArray(); for(Page p:pages)a.put(p.json()); j.put("pages",a); return j; }
    public static Notebook parse(String text) throws JSONException { Notebook n=new Notebook(); JSONArray a=new JSONObject(text).optJSONArray("pages"); if(a!=null)for(int i=0;i<a.length();i++)n.pages.add(Page.read(a.getJSONObject(i))); return n; }
    public static Notebook load(Context c) { try { File f=new File(c.getFilesDir(),"notebook.json"); byte[] bytes=new byte[(int)f.length()]; try(FileInputStream in=new FileInputStream(f)){ int offset=0,k; while(offset<bytes.length&&(k=in.read(bytes,offset,bytes.length-offset))>0)offset+=k; } Notebook n=parse(new String(bytes,"UTF-8")); if(n.pages.isEmpty())n.addPage(); return n; } catch(Exception e) { Notebook n=new Notebook(); n.addPage(); return n; } }
    public void save(Context c) throws Exception { File dir=c.getFilesDir(), tmp=new File(dir,"notebook.tmp"), dst=new File(dir,"notebook.json"); try(FileOutputStream out=new FileOutputStream(tmp)){out.write(json().toString().getBytes("UTF-8")); out.getFD().sync();} if(!tmp.renameTo(dst))throw new IOException("Could not save notebook"); }
}
