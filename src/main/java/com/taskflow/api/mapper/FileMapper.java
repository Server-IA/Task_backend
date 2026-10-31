package com.taskflow.api.mapper;

import com.taskflow.api.dto.response.FileResponse;
import com.taskflow.api.entity.StoredFile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.UUID;

@Mapper(uses = UserMapper.class)
public interface FileMapper {

    /** Prefijo de las referencias a archivos en el Markdown y en {@code avatarUrl}: {@code /files/<id>}. */
    String REFERENCE_PREFIX = "/files/";

    @Mapping(target = "size", source = "sizeBytes")
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "issueId", source = "issue.id")
    @Mapping(target = "reference", expression = "java(FileMapper.reference(file.getId()))")
    FileResponse toResponse(StoredFile file);

    /** {@code /files/<id>}: cómo se escribe un archivo en el Markdown ({@code ![nombre](/files/<id>)}). */
    static String reference(UUID id) {
        return id == null ? null : REFERENCE_PREFIX + id;
    }
}
