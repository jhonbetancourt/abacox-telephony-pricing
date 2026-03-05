package com.infomedia.abacox.telephonypricing.service;

import com.infomedia.abacox.telephonypricing.db.entity.FileInfo;
import com.infomedia.abacox.telephonypricing.db.repository.FileInfoRepository;
import com.infomedia.abacox.telephonypricing.service.common.CrudService;
import org.springframework.stereotype.Service;

@Service
public class FileInfoService extends CrudService<FileInfo, Long, FileInfoRepository> {
    public FileInfoService(FileInfoRepository repository) {
        super(repository);
    }
}