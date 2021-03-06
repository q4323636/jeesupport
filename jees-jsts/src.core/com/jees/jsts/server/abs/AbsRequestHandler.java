package com.jees.jsts.server.abs;

import com.jees.common.CommonContextHolder;
import com.jees.jsts.server.annotation.MessageRequest;
import com.jees.jsts.server.interf.IRequestHandler;
import com.jees.jsts.server.message.Message;
import com.jees.jsts.server.message.MessageDecoder;
import com.jees.jsts.server.message.MessageException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 客户端的消息处理器
 * @param <C>
 * @param <M>
 */
@Log4j2
public abstract class AbsRequestHandler< C extends ChannelHandlerContext, M > implements IRequestHandler< C, M > {
    private boolean						init;
    private Class                       clazz;
    private Map< Integer , Class< ? > > handlerClases;
    private Map< Integer , Method>		handlerMethod;

    public AbsRequestHandler() {
        if ( init ) return;
        init = true;

        handlerClases = new HashMap<>();
        handlerMethod = new HashMap<>();

        _command_register();
    }

    private void _command_register() {
        log.info( "服务器请求命令配置加载..." );
        Collection< Object > msg_coll = CommonContextHolder.getApplicationContext().getBeansWithAnnotation( MessageRequest.class ).values();
        if( msg_coll.size() == 0 )
            log.debug( "--未找到任何命令配置。" );
        msg_coll.forEach( b -> {
            Method[] mths = b.getClass().getMethods();
            for ( Method m : mths ) {
                MessageRequest gr = AnnotationUtils.findAnnotation( m , MessageRequest.class );
                if( gr != null ){
                    int cmd = gr.value();
                    if ( handlerClases.containsKey( cmd ) ) {
                        log.warn( "已存在相同的命令处理对象：CMD[" + cmd + "], MTH=[" + m.getName() + "]" );
                    } else {
                        handlerClases.put( cmd , b.getClass() );
                        handlerMethod.put( cmd , m );
                        log.debug( "--配置服务器命令: CMD[" + cmd + "], MTH=[" + m.getName() + "]" );
                    }
                }
            }
        } );
    }

    private static Message _msg_cast( Object _obj ){
        if( _obj == null ) return null;
        Message msg = null;

        if( _obj instanceof WebSocketFrame){
            msg = MessageDecoder.deserializer( ( WebSocketFrame ) _obj );
            msg.setType( Message.TYPE_WEBSOCKET );
        }else msg = ( Message ) _obj;

        return msg;
    }
    @SuppressWarnings( "unchecked" )
    @Override
    public void handler( C _ctx , Object _obj ) {
        Message msg = _msg_cast( _obj );

        if( msg == null ){
            exit( _ctx );
            return;
        }

        if( before( _ctx, (M)msg ) ) {
            int cmd = msg.getId();
            if ( handlerClases.containsKey( cmd ) ) {
                Method m = handlerMethod.get( cmd );
                try{
                    ReflectionUtils.invokeMethod( m , CommonContextHolder.getBean( handlerClases.get( cmd ) ), _ctx , msg );
                    after( _ctx );
                } catch ( MessageException me ) {
                    //程序异常，可以通知给客户端
                    log.error( "错误ME: I=[" + cmd + "] M=[" + m.getName() + "]:" + me.getMessage() );
                    error( _ctx, me );
                    // 后续两种错误不应该发生。RE为数据库操作错误,EX为程序错误
                } catch ( RuntimeException re ) {
                    log.error( "错误RE: I=[" + cmd + "] M=[" + m.getName() + "]" , re );
                } catch ( Exception ex ) {
                    log.error( "错误EX: I=[" + cmd + "] M=[" + m.getName() + "]" , ex );
                }
            } else log.warn( "命令没有注册：CMD=[" + cmd + "]" );
        }
    }

    public boolean validateId( int _id ){
        return handlerClases.containsKey( _id );
    }
}
