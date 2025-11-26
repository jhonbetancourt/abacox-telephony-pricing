package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import java.io.InputStream;

public record FileInfoData(String filename, InputStream content, long length) {
}