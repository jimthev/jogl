/*
 * Copyright (c) 2003 Sun Microsystems, Inc. All Rights Reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 
 * - Redistribution of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 * 
 * - Redistribution in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * 
 * Neither the name of Sun Microsystems, Inc. or the names of
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * 
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES,
 * INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. SUN
 * MICROSYSTEMS, INC. ("SUN") AND ITS LICENSORS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES. IN NO EVENT WILL SUN OR
 * ITS LICENSORS BE LIABLE FOR ANY LOST REVENUE, PROFIT OR DATA, OR FOR
 * DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL, INCIDENTAL OR PUNITIVE
 * DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY OF LIABILITY,
 * ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE, EVEN IF
 * SUN HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 * 
 * You acknowledge that this software is not designed or intended for use
 * in the design, construction, operation or maintenance of any nuclear
 * facility.
 * 
 * Sun gratefully acknowledges that this software was originally authored
 * and developed by Kenneth Bradley Russell and Christopher John Kline.
 */

package com.sun.opengl.impl.x11;

import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.security.*;
import java.util.ArrayList;
import java.util.List;
import javax.media.opengl.*;
import com.sun.opengl.impl.*;

public class X11GLDrawableFactory extends GLDrawableFactoryImpl {
  private static final boolean DEBUG = Debug.debug("X11GLDrawableFactory");

  // There is currently a bug on Linux/AMD64 distributions in glXGetProcAddressARB
  private static boolean isLinuxAMD64;

  static {
    NativeLibLoader.loadCore();

    AccessController.doPrivileged(new PrivilegedAction() {
        public Object run() {
          String os   = System.getProperty("os.name").toLowerCase();
          String arch = System.getProperty("os.arch").toLowerCase();
          if (os.startsWith("linux") && arch.equals("amd64")) {
            isLinuxAMD64 = true;
          }
          return null;
        }
      });
  }

  public X11GLDrawableFactory() {
    // Must initialize GLX support eagerly in case a pbuffer is the
    // first thing instantiated
    resetProcAddressTable(GLX.getGLXProcAddressTable());
  }

  private static final int MAX_ATTRIBS = 128;

  public AbstractGraphicsConfiguration chooseGraphicsConfiguration(GLCapabilities capabilities,
                                                                   GLCapabilitiesChooser chooser,
                                                                   AbstractGraphicsDevice absDevice) {
    if (capabilities == null) {
      capabilities = new GLCapabilities();
    }
    if (chooser == null) {
      chooser = new DefaultGLCapabilitiesChooser();
    }
    GraphicsDevice device = null;
    if (absDevice != null &&
        !(absDevice instanceof AWTGraphicsDevice)) {
      throw new IllegalArgumentException("This GLDrawableFactory accepts only AWTGraphicsDevice objects");
    }

    if ((absDevice == null) ||
        (((AWTGraphicsDevice) absDevice).getGraphicsDevice() == null)) {
      device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
    } else {
      device = ((AWTGraphicsDevice) absDevice).getGraphicsDevice();
    }

    int screen = X11SunJDKReflection.graphicsDeviceGetScreen(device);
    // Until we have a rock-solid visual selection algorithm written
    // in pure Java, we're going to provide the underlying window
    // system's selection to the chooser as a hint

    int[] attribs = glCapabilities2AttribList(capabilities, isMultisampleAvailable());
    XVisualInfo[] infos = null;
    GLCapabilities[] caps = null;
    int recommendedIndex = -1;
    lockToolkit();
    try {
      long display = getDisplayConnection();
      XVisualInfo recommendedVis = GLX.glXChooseVisual(display, screen, attribs, 0);
      int[] count = new int[1];
      XVisualInfo template = XVisualInfo.create();
      template.screen(screen);
      infos = GLX.XGetVisualInfo(display, GLX.VisualScreenMask, template, count, 0);
      if (infos == null) {
        throw new GLException("Error while enumerating available XVisualInfos");
      }
      caps = new GLCapabilities[infos.length];
      for (int i = 0; i < infos.length; i++) {
        caps[i] = xvi2GLCapabilities(display, infos[i]);
        // Attempt to find the visual chosen by glXChooseVisual
        if (recommendedVis != null && recommendedVis.visualid() == infos[i].visualid()) {
          recommendedIndex = i;
        }
      }
    } finally {
      unlockToolkit();
    }
    int chosen = chooser.chooseCapabilities(capabilities, caps, recommendedIndex);
    if (chosen < 0 || chosen >= caps.length) {
      throw new GLException("GLCapabilitiesChooser specified invalid index (expected 0.." + (caps.length - 1) + ")");
    }
    XVisualInfo vis = infos[chosen];
    if (vis == null) {
      throw new GLException("GLCapabilitiesChooser chose an invalid visual");
    }
    // FIXME: need to look at glue code and see type of this field
    long visualID = vis.visualid();
    // FIXME: the storage for the infos array, as well as that for the
    // recommended visual, is leaked; should free them here with XFree()

    // Now figure out which GraphicsConfiguration corresponds to this
    // visual by matching the visual ID
    GraphicsConfiguration[] configs = device.getConfigurations();
    for (int i = 0; i < configs.length; i++) {
      GraphicsConfiguration config = configs[i];
      if (config != null) {
        if (X11SunJDKReflection.graphicsConfigurationGetVisualID(config) == visualID) {
          return new AWTGraphicsConfiguration(config);
        }
      }
    }

    // Either we weren't able to reflectively introspect on the
    // X11GraphicsConfig or something went wrong in the steps above;
    // we're going to return null without signaling an error condition
    // in this case (although we should distinguish between the two
    // and possibly report more of an error in the latter case)
    return null;
  }

  public GLDrawable getGLDrawable(Object target,
                                  GLCapabilities capabilities,
                                  GLCapabilitiesChooser chooser) {
    if (target == null) {
      throw new IllegalArgumentException("Null target");
    }
    if (!(target instanceof Component)) {
      throw new IllegalArgumentException("GLDrawables not supported for objects of type " +
                                         target.getClass().getName() + " (only Components are supported in this implementation)");
    }
    return new X11OnscreenGLDrawable((Component) target);
  }

  public GLDrawableImpl createOffscreenDrawable(GLCapabilities capabilities,
                                                GLCapabilitiesChooser chooser) {
    return new X11OffscreenGLDrawable(capabilities, chooser);
  }

  private boolean pbufferSupportInitialized = false;
  private boolean canCreateGLPbuffer = false;
  public boolean canCreateGLPbuffer() {
    if (!pbufferSupportInitialized) {
      Runnable r = new Runnable() {
          public void run() {
            long display = getDisplayConnection();
            lockToolkit();
            try {
              int[] major = new int[1];
              int[] minor = new int[1];
              if (!GLX.glXQueryVersion(display, major, 0, minor, 0)) {
                throw new GLException("glXQueryVersion failed");
              }
              if (DEBUG) {
                System.err.println("!!! GLX version: major " + major[0] +
                                   ", minor " + minor[0]);
              }

              int screen = 0; // FIXME: provide way to specify this?

              // Work around bugs in ATI's Linux drivers where they report they
              // only implement GLX version 1.2 but actually do support pbuffers
              if (major[0] == 1 && minor[0] == 2) {
                String str = GLX.glXQueryServerString(display, screen, GLX.GLX_VENDOR);
                if (str != null && str.indexOf("ATI") >= 0) {
                  canCreateGLPbuffer = true;
                }
              } else {
                canCreateGLPbuffer = ((major[0] > 1) || (minor[0] > 2));
              }
        
              pbufferSupportInitialized = true;        
            } finally {
              unlockToolkit();
            }
          }
        };
      maybeDoSingleThreadedWorkaround(r);
    }
    return canCreateGLPbuffer;
  }

  public GLPbuffer createGLPbuffer(final GLCapabilities capabilities,
                                   final int initialWidth,
                                   final int initialHeight,
                                   final GLContext shareWith) {
    if (!canCreateGLPbuffer()) {
      throw new GLException("Pbuffer support not available with current graphics card");
    }
    final List returnList = new ArrayList();
    Runnable r = new Runnable() {
        public void run() {
          X11PbufferGLDrawable pbufferDrawable = new X11PbufferGLDrawable(capabilities,
                                                                          initialWidth,
                                                                          initialHeight);
          GLPbufferImpl pbuffer = new GLPbufferImpl(pbufferDrawable, shareWith);
          returnList.add(pbuffer);
        }
      };
    maybeDoSingleThreadedWorkaround(r);
    return (GLPbuffer) returnList.get(0);
  }

  public GLContext createExternalGLContext() {
    return new X11ExternalGLContext();
  }

  public boolean canCreateExternalGLDrawable() {
    return canCreateGLPbuffer();
  }

  public GLDrawable createExternalGLDrawable() {
    return new X11ExternalGLDrawable();
  }

  public long dynamicLookupFunction(String glFuncName) {
    long res = 0;
    if (!isLinuxAMD64) {
      res = GLX.glXGetProcAddressARB(glFuncName);
    }
    if (res == 0) {
      // GLU routines aren't known to the OpenGL function lookup
      res = GLX.dlsym(glFuncName);
    }
    return res;
  }

  public static GLCapabilities xvi2GLCapabilities(long display, XVisualInfo info) {
    int[] tmp = new int[1];
    int val = glXGetConfig(display, info, GLX.GLX_USE_GL, tmp, 0);
    if (val == 0) {
      // Visual does not support OpenGL
      return null;
    }
    val = glXGetConfig(display, info, GLX.GLX_RGBA, tmp, 0);
    if (val == 0) {
      // Visual does not support RGBA
      return null;
    }
    GLCapabilities res = new GLCapabilities();
    res.setDoubleBuffered(glXGetConfig(display, info, GLX.GLX_DOUBLEBUFFER,     tmp, 0) != 0);
    res.setStereo        (glXGetConfig(display, info, GLX.GLX_STEREO,           tmp, 0) != 0);
    // Note: use of hardware acceleration is determined by
    // glXCreateContext, not by the XVisualInfo. Optimistically claim
    // that all GLCapabilities have the capability to be hardware
    // accelerated.
    res.setHardwareAccelerated(true);
    res.setDepthBits     (glXGetConfig(display, info, GLX.GLX_DEPTH_SIZE,       tmp, 0));
    res.setStencilBits   (glXGetConfig(display, info, GLX.GLX_STENCIL_SIZE,     tmp, 0));
    res.setRedBits       (glXGetConfig(display, info, GLX.GLX_RED_SIZE,         tmp, 0));
    res.setGreenBits     (glXGetConfig(display, info, GLX.GLX_GREEN_SIZE,       tmp, 0));
    res.setBlueBits      (glXGetConfig(display, info, GLX.GLX_BLUE_SIZE,        tmp, 0));
    res.setAlphaBits     (glXGetConfig(display, info, GLX.GLX_ALPHA_SIZE,       tmp, 0));
    res.setAccumRedBits  (glXGetConfig(display, info, GLX.GLX_ACCUM_RED_SIZE,   tmp, 0));
    res.setAccumGreenBits(glXGetConfig(display, info, GLX.GLX_ACCUM_GREEN_SIZE, tmp, 0));
    res.setAccumBlueBits (glXGetConfig(display, info, GLX.GLX_ACCUM_BLUE_SIZE,  tmp, 0));
    res.setAccumAlphaBits(glXGetConfig(display, info, GLX.GLX_ACCUM_ALPHA_SIZE, tmp, 0));
    if (isMultisampleAvailable()) {
      res.setSampleBuffers(glXGetConfig(display, info, GLX.GLX_SAMPLE_BUFFERS_ARB, tmp, 0) != 0);
      res.setNumSamples   (glXGetConfig(display, info, GLX.GLX_SAMPLES_ARB,        tmp, 0));
    }
    return res;
  }

  public static int[] glCapabilities2AttribList(GLCapabilities caps,
                                                boolean isMultisampleAvailable) {
    int colorDepth = (caps.getRedBits() +
                      caps.getGreenBits() +
                      caps.getBlueBits());
    if (colorDepth < 15) {
      throw new GLException("Bit depths < 15 (i.e., non-true-color) not supported");
    }
    int[] res = new int[MAX_ATTRIBS];
    int idx = 0;
    res[idx++] = GLX.GLX_RGBA;
    if (caps.getDoubleBuffered()) {
      res[idx++] = GLX.GLX_DOUBLEBUFFER;
    }
    if (caps.getStereo()) {
      res[idx++] = GLX.GLX_STEREO;
    }
    res[idx++] = GLX.GLX_RED_SIZE;
    res[idx++] = caps.getRedBits();
    res[idx++] = GLX.GLX_GREEN_SIZE;
    res[idx++] = caps.getGreenBits();
    res[idx++] = GLX.GLX_BLUE_SIZE;
    res[idx++] = caps.getBlueBits();
    res[idx++] = GLX.GLX_ALPHA_SIZE;
    res[idx++] = caps.getAlphaBits();
    res[idx++] = GLX.GLX_DEPTH_SIZE;
    res[idx++] = caps.getDepthBits();
    res[idx++] = GLX.GLX_STENCIL_SIZE;
    res[idx++] = caps.getStencilBits();
    res[idx++] = GLX.GLX_ACCUM_RED_SIZE;
    res[idx++] = caps.getAccumRedBits();
    res[idx++] = GLX.GLX_ACCUM_GREEN_SIZE;
    res[idx++] = caps.getAccumGreenBits();
    res[idx++] = GLX.GLX_ACCUM_BLUE_SIZE;
    res[idx++] = caps.getAccumBlueBits();
    if (isMultisampleAvailable && caps.getSampleBuffers()) {
      res[idx++] = GLXExt.GLX_SAMPLE_BUFFERS_ARB;
      res[idx++] = GL.GL_TRUE;
      res[idx++] = GLXExt.GLX_SAMPLES_ARB;
      res[idx++] = caps.getNumSamples();
    }
    res[idx++] = 0;
    return res;
  }

  public void lockToolkit() {
    if (!Java2D.isOGLPipelineActive() || !Java2D.isQueueFlusherThread()) {
      JAWT.getJAWT().Lock();
    }
  }

  public void unlockToolkit() {
    if (!Java2D.isOGLPipelineActive() || !Java2D.isQueueFlusherThread()) {
      JAWT.getJAWT().Unlock();
    }
  }

  public void lockAWTForJava2D() {
    lockToolkit();
  }
  public void unlockAWTForJava2D() {
    unlockToolkit();
  }

  // Display connection for use by visual selection algorithm and by all offscreen surfaces
  private static long staticDisplay;
  public static long getDisplayConnection() {
    if (staticDisplay == 0) {
      getX11Factory().lockToolkit();
      try {
        staticDisplay = GLX.XOpenDisplay(null);
      } finally {
        getX11Factory().unlockToolkit();
      }
      if (staticDisplay == 0) {
        throw new GLException("Unable to open default display, needed for visual selection and offscreen surface handling");
      }
    }
    return staticDisplay;
  }

  private static boolean checkedMultisample;
  private static boolean multisampleAvailable;
  public static boolean isMultisampleAvailable() {
    if (!checkedMultisample) {
      long display = getDisplayConnection();
      String exts = GLX.glXGetClientString(display, GLX.GLX_EXTENSIONS);
      if (exts != null) {
        multisampleAvailable = (exts.indexOf("GLX_ARB_multisample") >= 0);
      }
      checkedMultisample = true;
    }
    return multisampleAvailable;
  }

  private static String glXGetConfigErrorCode(int err) {
    switch (err) {
      case GLX.GLX_NO_EXTENSION:  return "GLX_NO_EXTENSION";
      case GLX.GLX_BAD_SCREEN:    return "GLX_BAD_SCREEN";
      case GLX.GLX_BAD_ATTRIBUTE: return "GLX_BAD_ATTRIBUTE";
      case GLX.GLX_BAD_VISUAL:    return "GLX_BAD_VISUAL";
      default:                return "Unknown error code " + err;
    }
  }

  public static int glXGetConfig(long display, XVisualInfo info, int attrib, int[] tmp, int tmp_offset) {
    if (display == 0) {
      throw new GLException("No display connection");
    }
    int res = GLX.glXGetConfig(display, info, attrib, tmp, tmp_offset);
    if (res != 0) {
      throw new GLException("glXGetConfig failed: error code " + glXGetConfigErrorCode(res));
    }
    return tmp[tmp_offset];
  }

  public static X11GLDrawableFactory getX11Factory() {
    return (X11GLDrawableFactory) getFactory();
  }

  private void maybeDoSingleThreadedWorkaround(Runnable action) {
    if (Threading.isSingleThreaded() &&
        !Threading.isOpenGLThread()) {
      Threading.invokeOnOpenGLThread(action);
    } else {
      action.run();
    }
  }
}
