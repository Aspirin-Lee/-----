package org.labday.app;
import android.graphics.*;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;
public class Media {
    public static Bitmap bitmap(File file,int max)throws IOException{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("无法解码图片");
        BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=1;
        while(bounds.outWidth/opts.inSampleSize>max||bounds.outHeight/opts.inSampleSize>max)opts.inSampleSize*=2;
        Bitmap original=BitmapFactory.decodeFile(file.getPath(),opts);if(original==null)throw new IOException("图片不可读");
        Matrix matrix=new Matrix();
        try{
            int orientation=new ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
            switch(orientation){
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;
                case ExifInterface.ORIENTATION_ROTATE_180:matrix.setRotate(180);break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;
                case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setRotate(90);matrix.postScale(-1,1);break;
                case ExifInterface.ORIENTATION_ROTATE_90:matrix.setRotate(90);break;
                case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setRotate(270);matrix.postScale(-1,1);break;
                case ExifInterface.ORIENTATION_ROTATE_270:matrix.setRotate(270);break;
                default:break;
            }
        }catch(IOException ignored){}
        if(matrix.isIdentity())return original;
        Bitmap rotated=Bitmap.createBitmap(original,0,0,original.getWidth(),original.getHeight(),matrix,true);
        if(rotated!=original)original.recycle();return rotated;
    }
}
