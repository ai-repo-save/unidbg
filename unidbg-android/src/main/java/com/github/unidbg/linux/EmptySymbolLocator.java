package com.github.unidbg.linux;

import net.fornwall.jelf.ElfSymbol;
import net.fornwall.jelf.SymbolLocator;

public class EmptySymbolLocator implements SymbolLocator {

    @Override
    public ElfSymbol getELFSymbol(int index) {
        return null;
    }

    @Override
    public ElfSymbol getELFSymbolByName(String name) {
        return null;
    }

    @Override
    public ElfSymbol getELFSymbolByAddr(long addr) {
        return null;
    }
}
