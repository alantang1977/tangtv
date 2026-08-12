package com.fongmi.android.tv.api.loader;

import dalvik.system.DexClassLoader;

final class CspDexClassLoader extends DexClassLoader {

    CspDexClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        super(dexPath, optimizedDirectory, librarySearchPath, parent);
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!CspClassLoadingPolicy.isChildFirst(name)) return super.loadClass(name, resolve);
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
            try {
                loaded = findClass(name);
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, resolve);
            }
        }
        if (resolve) resolveClass(loaded);
        return loaded;
    }
}
