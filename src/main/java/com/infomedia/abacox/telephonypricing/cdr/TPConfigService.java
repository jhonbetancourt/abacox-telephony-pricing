package com.infomedia.abacox.telephonypricing.cdr;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class TPConfigService {

    // These constants are based on the PHP defines
    private static final Long TIPOTELE_LOCAL = 1L;
    private static final Long TIPOTELE_LOCAL_EXT = 6L;
    private static final Long TIPOTELE_NACIONAL = 2L;
    private static final Long TIPOTELE_CELULAR = 3L;
    private static final Long TIPOTELE_CELUFIJO = 7L;
    private static final Long TIPOTELE_INTERNACIONAL = 4L;
    private static final Long TIPOTELE_SATELITAL = 5L;
    private static final Long TIPOTELE_ESPECIALES = 8L;
    private static final Long TIPOTELE_ERRORES = 9L;
    private static final String ASUMIDO = "asumido";
    private static final Long MAX_EXTENSION = 1000000L;


    @Cacheable("tipoteleLocal")
    public Long getTipoteleLocal() {
        return TIPOTELE_LOCAL;
    }


    @Cacheable("tipoteleLocalExt")
    public Long getTipoteleLocalExt() {
        return TIPOTELE_LOCAL_EXT;
    }


    @Cacheable("tipoteleNacional")
    public Long getTipoteleNacional() {
        return TIPOTELE_NACIONAL;
    }


    @Cacheable("tipoteleCelular")
    public Long getTipoteleCelular() {
        return TIPOTELE_CELULAR;
    }


    @Cacheable("tipoteleCelufijo")
    public Long getTipoteleCelufijo() {
        return TIPOTELE_CELUFIJO;
    }


    @Cacheable("tipoteleInternacional")
    public Long getTipoteleInternacional() {
        return TIPOTELE_INTERNACIONAL;
    }


    @Cacheable("tipoteleSatelital")
    public Long getTipoteleSatelital() {
        return TIPOTELE_SATELITAL;
    }


    @Cacheable("tipoteleEspeciales")
    public Long getTipoteleEspeciales() {
        return TIPOTELE_ESPECIALES;
    }


    @Cacheable("tipoteleErrores")
    public Long getTipoteleErrores() {
        return TIPOTELE_ERRORES;
    }


    @Cacheable("asumido")
    public String getAsumido() {
        return ASUMIDO;
    }


    @Cacheable("maxExtension")
    public Long getMaxExtension() {
        return MAX_EXTENSION;
    }
}