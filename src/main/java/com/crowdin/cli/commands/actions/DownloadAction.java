package com.crowdin.cli.commands.actions;

import com.crowdin.cli.client.CrowdinProjectFull;
import com.crowdin.cli.client.LanguageMapping;
import com.crowdin.cli.client.ProjectClient;
import com.crowdin.cli.commands.NewAction;
import com.crowdin.cli.commands.Outputter;
import com.crowdin.cli.commands.functionality.FilesInterface;
import com.crowdin.cli.commands.functionality.ProjectFilesUtils;
import com.crowdin.cli.commands.functionality.PropertiesBeanUtils;
import com.crowdin.cli.commands.functionality.RequestBuilder;
import com.crowdin.cli.commands.functionality.SourcesUtils;
import com.crowdin.cli.commands.functionality.TranslationsUtils;
import com.crowdin.cli.properties.FileBean;
import com.crowdin.cli.properties.PropertiesWithFiles;
import com.crowdin.cli.properties.PseudoLocalization;
import com.crowdin.cli.utils.PlaceholderUtil;
import com.crowdin.cli.utils.Utils;
import com.crowdin.cli.utils.console.ConsoleSpinner;
import com.crowdin.client.languages.model.Language;
import com.crowdin.client.sourcefiles.model.Branch;
import com.crowdin.client.translations.model.BuildProjectTranslationRequest;
import com.crowdin.client.translations.model.CrowdinTranslationCreateProjectBuildForm;
import com.crowdin.client.translations.model.ProjectBuild;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.crowdin.cli.BaseCli.RESOURCE_BUNDLE;
import static com.crowdin.cli.utils.console.ExecutionStatus.ERROR;
import static com.crowdin.cli.utils.console.ExecutionStatus.OK;
import static com.crowdin.cli.utils.console.ExecutionStatus.WARNING;

class DownloadAction implements NewAction<PropertiesWithFiles, ProjectClient> {

    private FilesInterface files;
    private boolean noProgress;
    private String languageId;
    private boolean pseudo;
    private String branchName;
    private boolean ignoreMatch;
    private boolean isVerbose;
    private boolean plainView;

    private Outputter out;

    public DownloadAction(
            FilesInterface files, boolean noProgress, String languageId, boolean pseudo, String branchName,
            boolean ignoreMatch, boolean isVerbose, boolean plainView
    ) {
        this.files = files;
        this.noProgress = noProgress || plainView;
        this.languageId = languageId;
        this.pseudo = pseudo;
        this.branchName = branchName;
        this.ignoreMatch = ignoreMatch;
        this.isVerbose = isVerbose;
        this.plainView = plainView;
    }

    @Override
    public void act(Outputter out, PropertiesWithFiles pb, ProjectClient client) {
        this.out = out;
        boolean isOrganization = PropertiesBeanUtils.isOrganization(pb.getBaseUrl());

        CrowdinProjectFull project = ConsoleSpinner
            .execute(out, "message.spinner.fetching_project_info", "error.collect_project_info",
                this.noProgress, this.plainView, client::downloadFullProject);

        if (!project.isManagerAccess()) {
            if (!plainView) {
                out.println(WARNING.withIcon(RESOURCE_BUNDLE.getString("message.no_manager_access")));
                return;
            } else {
                throw new RuntimeException(RESOURCE_BUNDLE.getString("message.no_manager_access"));
            }
        }

        PlaceholderUtil placeholderUtil =
            new PlaceholderUtil(
                project.getSupportedLanguages(), project.getProjectLanguages(true), pb.getBasePath());

        Optional<Language> language = Optional.ofNullable(languageId)
            .map(lang -> project.findLanguageById(lang, true)
                .orElseThrow(() -> new RuntimeException(
                    String.format(RESOURCE_BUNDLE.getString("error.language_not_exist"), lang))));
        Optional<Branch> branch = Optional.ofNullable(this.branchName)
            .map(br -> project.findBranchByName(br)
                .orElseThrow(() -> new RuntimeException(RESOURCE_BUNDLE.getString("error.not_found_branch"))));

        LanguageMapping serverLanguageMapping = project.getLanguageMapping();

        List<Language> forLanguages = language
            .map(Collections::singletonList)
            .orElse(project.getProjectLanguages(true));

        List<Pair<Map<String, String>, Pair<File, List<String>>>> fileBeansWithDownloadedFiles = new ArrayList<>();
        List<File> tempDirs = new ArrayList<>();
        try {
            if (pseudo) {
                if (!plainView) {
                    out.println(OK.withIcon(RESOURCE_BUNDLE.getString("message.build_archive_pseudo")));
                }
                PseudoLocalization pl = pb.getPseudoLocalization();
                BuildProjectTranslationRequest request = (pl != null)
                    ? RequestBuilder.crowdinTranslationCreateProjectPseudoBuildForm(
                        true, pl.getLengthCorrection(), pl.getPrefix(), pl.getSuffix(), pl.getCharTransformation())
                    : RequestBuilder.crowdinTranslationCreateProjectPseudoBuildForm(true, null, null, null, null);
                Pair<File, List<String>> downloadedFiles = this.download(request, client, pb.getBasePath());
                for (FileBean fb : pb.getFiles()) {
                    Map<String, String> filesWithMapping = this.getFiles(fb, pb.getBasePath(), serverLanguageMapping, forLanguages, placeholderUtil);
                    fileBeansWithDownloadedFiles.add(Pair.of(filesWithMapping, downloadedFiles));
                }
                tempDirs.add(downloadedFiles.getLeft());
            } else {
                if (!plainView) {
                    out.println((languageId != null)
                        ? OK.withIcon(String.format(RESOURCE_BUNDLE.getString("message.build_language_archive"), languageId))
                        : OK.withIcon(RESOURCE_BUNDLE.getString("message.build_archive")));
                }
                CrowdinTranslationCreateProjectBuildForm templateRequest = new CrowdinTranslationCreateProjectBuildForm();
                language
                    .map(Language::getId)
                    .map(Collections::singletonList)
                    .ifPresent(templateRequest::setTargetLanguageIds);
                branch
                    .map(Branch::getId)
                    .ifPresent(templateRequest::setBranchId);
                pb.getFiles().stream()
                    .map(fb -> Triple.of(fb.getSkipTranslatedOnly(), fb.getSkipUntranslatedFiles(), fb.getExportApprovedOnly()))
                    .distinct()
                    .forEach(downloadConfiguration -> {
                        CrowdinTranslationCreateProjectBuildForm buildRequest = RequestBuilder.crowdinTranslationCreateProjectBuildForm(templateRequest);
                        buildRequest.setSkipUntranslatedStrings(downloadConfiguration.getLeft());
                        buildRequest.setSkipUntranslatedFiles(downloadConfiguration.getMiddle());
                        if (isOrganization) {
                            if (downloadConfiguration.getRight() != null && downloadConfiguration.getRight()) {
                                buildRequest.setExportWithMinApprovalsCount(1);
                            }
                        } else {
                            buildRequest.setExportApprovedOnly(downloadConfiguration.getRight());
                        }
                        Pair<File, List<String>> downloadedFiles = this.download(buildRequest, client, pb.getBasePath());
                        for (FileBean fb : pb.getFiles()) {
                            if (fb.getSkipTranslatedOnly() == downloadConfiguration.getLeft()
                                && fb.getSkipUntranslatedFiles() == downloadConfiguration.getMiddle()
                                && fb.getExportApprovedOnly() == downloadConfiguration.getRight()) {

                                Map<String, String> filesWithMapping =
                                    this.getFiles(fb, pb.getBasePath(), serverLanguageMapping, forLanguages, placeholderUtil);
                                fileBeansWithDownloadedFiles.add(Pair.of(filesWithMapping, downloadedFiles));
                            }
                        }
                        tempDirs.add(downloadedFiles.getLeft());
                    });
            }

            Map<Long, String> directoryPaths = (branch.isPresent())
                ? ProjectFilesUtils.buildDirectoryPaths(project.getDirectories())
                : ProjectFilesUtils.buildDirectoryPaths(project.getDirectories(), project.getBranches());
            Map<String, List<String>> allProjectTranslations = ProjectFilesUtils
                .buildAllProjectTranslations(
                    project.getFiles(), directoryPaths, branch.map(Branch::getId),
                    placeholderUtil, serverLanguageMapping, pb.getBasePath());

            Map<String, List<String>> totalOmittedFiles = new TreeMap<>();
            List<String> totalOmittedFilesNoSources = new ArrayList<>();

            for (Pair<Map<String, String>, Pair<File, List<String>>> data : fileBeansWithDownloadedFiles) {
                Map<String, String> filesWithMapping = data.getKey();
                File tempDir = data.getValue().getKey();
                List<String> downloadedFiles = data.getValue().getValue();

                Pair<Map<File, File>, List<String>> result =
                    sortFiles(downloadedFiles, filesWithMapping, pb.getBasePath(), tempDir.getAbsolutePath() + Utils.PATH_SEPARATOR);
                new TreeMap<>(result.getLeft()).forEach((fromFile, toFile) -> { //files to extract
                    files.copyFile(fromFile, toFile);
                    if (!plainView) {
                        out.println(OK.withIcon(
                            String.format(
                                RESOURCE_BUNDLE.getString("message.extracted_file"),
                                StringUtils.removeStart(toFile.getAbsolutePath(), pb.getBasePath()))));
                    } else {
                        out.println(StringUtils.removeStart(toFile.getAbsolutePath(), pb.getBasePath()));
                    }
                });

                Pair<Map<String, List<String>>, List<String>> omittedFiles =
                    this.sortOmittedFiles(result.getRight(), allProjectTranslations);
                Map<String, List<String>> allOmittedFiles = new TreeMap<>(omittedFiles.getLeft());
                List<String> allOmittedFilesNoSources = omittedFiles.getRight();
                for (String sourceKey : allOmittedFiles.keySet()) {
                    if (!totalOmittedFiles.containsKey(sourceKey)) {
                        totalOmittedFiles.put(sourceKey, allOmittedFiles.get(sourceKey));
                    } else {
                        totalOmittedFiles.get(sourceKey).retainAll(allOmittedFiles.get(sourceKey));
                    }
                }
                for (String sourceKey : totalOmittedFiles.keySet()) {
                    if (!allOmittedFiles.containsKey(sourceKey)) {
                        totalOmittedFiles.put(sourceKey, new ArrayList<>());
                    }
                }
                totalOmittedFilesNoSources.retainAll(allOmittedFilesNoSources);
            }

            if (!ignoreMatch && !plainView) {
                if (!totalOmittedFiles.isEmpty()) {
                    out.println(WARNING.withIcon(RESOURCE_BUNDLE.getString("message.downloaded_files_omitted")));
                    totalOmittedFiles.forEach((file, translations) -> {
                        if (translations.isEmpty()) {
                            return;
                        }
                        out.println(String.format(
                            RESOURCE_BUNDLE.getString("message.item_list_with_count"), file, translations.size()));
                        if (isVerbose) {
                            translations.forEach(trans -> out.println(
                                String.format(RESOURCE_BUNDLE.getString("message.item_list"), trans)));
                        }
                    });
                }
                if (!totalOmittedFilesNoSources.isEmpty()) {
                    out.println(
                        WARNING.withIcon(
                            RESOURCE_BUNDLE.getString("message.downloaded_files_omitted_without_sources")));
                    totalOmittedFilesNoSources.forEach(file ->
                        out.println(String.format(RESOURCE_BUNDLE.getString("message.item_list"), file)));
                }
            }
        } finally {
            try {
                for (File tempDir : tempDirs) {
                    files.deleteDirectory(tempDir);
                }
            } catch (IOException e) {
                throw new RuntimeException(RESOURCE_BUNDLE.getString("error.clearing_temp"), e);
            }
        }
    }

    private Pair<File, List<String>> download(BuildProjectTranslationRequest request, ProjectClient client, String basePath) {
        ProjectBuild projectBuild = buildTranslation(client, request);
        String randomHash = RandomStringUtils.random(11, false, true);
        File baseTempDir =
            new File(StringUtils.removeEnd(
                basePath,
                Utils.PATH_SEPARATOR) + Utils.PATH_SEPARATOR + "CrowdinTranslations_" + randomHash + Utils.PATH_SEPARATOR);
        String downloadedZipArchivePath =
            StringUtils.removeEnd(basePath, Utils.PATH_SEPARATOR) + Utils.PATH_SEPARATOR + "CrowdinTranslations_" + randomHash + ".zip";
        File downloadedZipArchive = new File(downloadedZipArchivePath);

        this.downloadTranslations(client, projectBuild.getId(), downloadedZipArchivePath);

        List<String> downloadedFilesProc = files.extractZipArchive(downloadedZipArchive, baseTempDir)
            .stream()
            .map(f -> StringUtils
                .removeStart(f.getAbsolutePath(), baseTempDir.getAbsolutePath() + Utils.PATH_SEPARATOR))
            .collect(Collectors.toList());

        try {
            files.deleteFile(downloadedZipArchive);
        } catch (IOException e) {
            out.println(ERROR.withIcon(String.format(RESOURCE_BUNDLE.getString("error.deleting_archive"), downloadedZipArchive)));
        }
        return Pair.of(baseTempDir, downloadedFilesProc);
    }

    private Map<String, String> getFiles(
        FileBean fb, String basePath, LanguageMapping serverLanguageMapping, List<Language> forLanguages, PlaceholderUtil placeholderUtil
    ) {
        List<String> sources =
            SourcesUtils.getFiles(basePath, fb.getSource(), fb.getIgnore(), placeholderUtil)
                .map(File::getAbsolutePath)
                .collect(Collectors.toList());
        LanguageMapping localLanguageMapping = LanguageMapping.fromConfigFileLanguageMapping(fb.getLanguagesMapping());
        LanguageMapping languageMapping = LanguageMapping.populate(localLanguageMapping, serverLanguageMapping);
        Map<String, String> translationReplace =
            fb.getTranslationReplace() != null ? fb.getTranslationReplace() : new HashMap<>();
        return this.doTranslationMapping(
            forLanguages, fb.getTranslation(), serverLanguageMapping, languageMapping,
            translationReplace, sources, fb.getSource(), basePath, placeholderUtil);
    }

    private ProjectBuild buildTranslation(ProjectClient client, BuildProjectTranslationRequest request) {
        return ConsoleSpinner.execute(out, "message.spinner.fetching_project_info",
            "error.collect_project_info", this.noProgress, this.plainView, () -> {
                ProjectBuild build = client.startBuildingTranslation(request);

                while (!build.getStatus().equalsIgnoreCase("finished")) {
                    ConsoleSpinner.update(
                        String.format(RESOURCE_BUNDLE.getString("message.building_translation"),
                            Math.toIntExact(build.getProgress())));
                    Thread.sleep(100);
                    build = client.checkBuildingTranslation(build.getId());
                }
                ConsoleSpinner.update(String.format(RESOURCE_BUNDLE.getString("message.building_translation"), 100));
                return build;
            });
    }

    private Pair<Map<File, File>, List<String>> sortFiles(
        List<String> downloadedFiles,
        Map<String, String> filesWithMapping,
        String basePath,
        String baseTempDir
    ) {
        Map<File, File> fileMapping = downloadedFiles
            .stream()
            .filter(filesWithMapping::containsKey)
            .collect(Collectors.toMap(
                downloadedFile -> new File(baseTempDir + downloadedFile),
                downloadedFile -> new File(basePath + filesWithMapping.get(downloadedFile))));
        List<String> omittedFiles = downloadedFiles
            .stream()
            .filter(downloadedFile -> !filesWithMapping.containsKey(downloadedFile))
            .collect(Collectors.toList());
        return new ImmutablePair<>(fileMapping, omittedFiles);
    }

    private Pair<Map<String, List<String>>, List<String>> sortOmittedFiles(
        List<String> omittedFiles,
        Map<String, List<String>> allProjectTranslations
    ) {
        Map<String, List<String>> allOmittedFiles = new HashMap<>();
        List<String> allOmittedFilesNoSources = new ArrayList<>();
        for (String omittedFile : omittedFiles) {
            boolean isFound = false;
            for (Map.Entry<String, List<String>> entry : allProjectTranslations.entrySet()) {
                if (entry.getValue().contains(omittedFile)) {
                    isFound = true;
                    allOmittedFiles.putIfAbsent(entry.getKey(), new ArrayList<>());
                    allOmittedFiles.get(entry.getKey()).add(StringUtils.removeStart(omittedFile, Utils.PATH_SEPARATOR));
                }
            }
            if (!isFound) {
                allOmittedFilesNoSources.add(StringUtils.removeStart(omittedFile, Utils.PATH_SEPARATOR));
            }
        }
        return new ImmutablePair<>(allOmittedFiles, allOmittedFilesNoSources);
    }

    private Map<String, String> doTranslationMapping(
        List<Language> languages,
        String translation,
        LanguageMapping projLanguageMapping,
        LanguageMapping languageMapping,
        Map<String, String> translationReplace,
        List<String> sources,
        String source,
        String basePath,
        PlaceholderUtil placeholderUtil
    ) {
        Map<String, String> mapping = new HashMap<>();

        for (Language language : languages) {

            if (!StringUtils.startsWith(translation, Utils.PATH_SEPARATOR)) {
                translation = Utils.PATH_SEPARATOR + translation;
            }
            String translationProject1 =
                placeholderUtil.replaceLanguageDependentPlaceholders(translation, projLanguageMapping, language);
            String translationFile1 =
                placeholderUtil.replaceLanguageDependentPlaceholders(translation, languageMapping, language);

            for (String projectFile : sources) {
                String file = StringUtils.removeStart(projectFile, basePath);
                String translationProject2 = TranslationsUtils.replaceDoubleAsterisk(source, translationProject1, file);
                String translationFile2 = TranslationsUtils.replaceDoubleAsterisk(source, translationFile1, file);

                translationProject2 =
                    placeholderUtil.replaceFileDependentPlaceholders(translationProject2, new File(projectFile));
                translationFile2 =
                    placeholderUtil.replaceFileDependentPlaceholders(translationFile2, new File(projectFile));
                translationFile2 = PropertiesBeanUtils.useTranslationReplace(translationFile2, translationReplace);
                mapping.put(translationProject2, translationFile2);
            }
        }
        return mapping;
    }

    private void downloadTranslations(ProjectClient client, Long buildId, String archivePath) {
        URL url = ConsoleSpinner
            .execute(out, "message.spinner.downloading_translation", "error.downloading_file",
                this.noProgress, this.plainView, () -> client.downloadBuild(buildId));
        try (InputStream data = url.openStream()) {
            files.writeToFile(archivePath, data);
        } catch (IOException e) {
            throw new RuntimeException(String.format(RESOURCE_BUNDLE.getString("error.write_file"), archivePath), e);
        }
    }
}
