package org.flathub.api.service;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.flathub.api.model.Arch;
import org.flathub.api.model.FlatpakRefRemoteInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeoutException;


@Service
public class LocalFlatpakInstallationServiceImpl implements LocalFlatpakInstallationService {


  private static final Logger LOGGER = LoggerFactory.getLogger(LocalFlatpakInstallationServiceImpl.class);

  private static final int[] FLATPAK_EXIT_VALUES = {0, 1};


  private static final String REMOTE_INFO_REF = "Ref:";
  private static final String REMOTE_INFO_ID = "ID:";
  private static final String REMOTE_INFO_ARCH = "Arch:";
  private static final String REMOTE_INFO_BRANCH_ = "Branch:";
  private static final String REMOTE_INFO_COLLECTION = "Collection:";
  private static final String REMOTE_INFO_COLLECTION_ID = "Collection ID:";
  private static final String REMOTE_INFO_DATE = "Date:";
  private static final String REMOTE_INFO_SUBJECT = "Subject:";
  private static final String REMOTE_INFO_COMMIT = "Commit:";
  private static final String REMOTE_INFO_PARENT = "Parent:";
  private static final String REMOTE_INFO_DOWNLOAD_SIZE = "Download size:";
  private static final String REMOTE_INFO_INSTALLED_SIZE = "Installed size:";
  private static final String REMOTE_INFO_DOWNLOAD = "Download:";
  private static final String REMOTE_INFO_INSTALLED = "Installed:";
  private static final String REMOTE_INFO_RUNTIME = "Runtime:";
  private static final String REMOTE_INFO_SDK = "Sdk:";
  private static final String REMOTE_INFO_HISTORY = "History:";
  private static final String REMOTE_INFO_EOL_MESSAGE_PREFIX= "eol=";
  private static final String REMOTE_INFO_EOL= "End-of-life:";
  private static final String REMOTE_INFO_EOL_REBASE= "End-of-life-rebase:";



  @SuppressWarnings("unused")
  @Value("${flatpak.flatpak-command}")
  private String flatpakCommand;

  private String[] remoteInfoCache;
  private LocalDateTime remoteInfoCacheDate;

  @SuppressWarnings("OptionalIsPresent")
  @Override
  public List<FlatpakRefRemoteInfo> getAllQuickBasicRemoteInfoByRemote(String remote) {

    String command = flatpakCommand + " --system remote-ls --columns=ref:f,commit:f,installed-size:f,download-size:f,options:f --arch=* " + remote;

    Optional<FlatpakRefRemoteInfo> remoteInfo;
    ArrayList<FlatpakRefRemoteInfo> remoteInfoList = new ArrayList<>();

    LOGGER.debug("Getting quick basic remote info for all refs in remote " + remote);

    try {

      if (remoteInfoCache == null || remoteInfoCacheDate == null ||
        remoteInfoCacheDate.isBefore(LocalDateTime.now().minusMinutes(5))) {

        String result = execToString(command);
        remoteInfoCache = result.split("[\\r\\n]+");
        remoteInfoCacheDate = LocalDateTime.now();

      }

      for (String tabulatedRemoteInfo : remoteInfoCache) {

        remoteInfo = this.parseBasicRemoteInfoLine(tabulatedRemoteInfo);

        if (remoteInfo.isPresent()) {
          remoteInfoList.add(remoteInfo.get());
        }

      }

      return remoteInfoList;
    } catch (Exception e) {
      LOGGER.error("Error getting basic remote info for all refs in remote " + remote + ": ", e);
      return remoteInfoList;
    }
  }

  public Optional<FlatpakRefRemoteInfo> getQuickBasicRemoteInfoByRemoteAndArchAndId(String remote, Arch arch, String id)  {

    String command = flatpakCommand + " --system remote-ls --columns=ref:f,commit:f,installed-size:f,download-size:f,options:f --arch=* " + remote;

    Optional<FlatpakRefRemoteInfo> remoteInfo;

    LOGGER.debug("Getting quick basic remote info for id " + id + "for arch " + arch + " and remote " + remote);

    try {

      if (remoteInfoCache == null || remoteInfoCacheDate == null ||
        remoteInfoCacheDate.isBefore(LocalDateTime.now().minusMinutes(5))) {

        String result = execToString(command);
        remoteInfoCache = result.split("[\\r\\n]+");
        remoteInfoCacheDate = LocalDateTime.now();

      }

      for (String tabulatedRemoteInfo : remoteInfoCache) {

        remoteInfo = this.parseBasicRemoteInfoLine(tabulatedRemoteInfo);

        if (remoteInfo.isPresent() && remoteInfo.get().getId().equalsIgnoreCase(id) &&
          remoteInfo.get().getArch().equalsIgnoreCase(arch.toString())) {
          return remoteInfo;
        }

      }

      return Optional.empty();
    } catch (Exception e) {
      LOGGER.error("There was an error getting quick basic remote info for id " + id + " and arch " + arch + " and remote " + remote, e);
      return Optional.empty();
    }
  }

  @Override
  public Optional<FlatpakRefRemoteInfo> getRemoteInfoByRemoteAndArchAndId(String remote, Arch arch, String id, boolean retryIfFailed) {


    Optional<FlatpakRefRemoteInfo> completeRemoteInfo;

    completeRemoteInfo = this.getRemoteInfoByRemoteAndArchAndId(remote, arch, id);

    if (!completeRemoteInfo.isPresent() && retryIfFailed) {

      try {
        LOGGER.warn("Waiting 5 secs to try get the remote-info again for " + id + " ...");
        Thread.sleep(5000);

        completeRemoteInfo = this.getRemoteInfoByRemoteAndArchAndId(remote, arch, id);

      } catch (InterruptedException e) {
        LOGGER.error("There was an error while waiting to execute remote-info", e);
      }
    }

    return completeRemoteInfo;
  }



  @Override
  public Optional<FlatpakRefRemoteInfo> getRemoteInfoByRemoteAndArchAndId(String remote, Arch arch, String id) {

    String command = flatpakCommand + " --system remote-info --log --arch " + arch.toString() + " " + remote + " " + id;
    String result;

    LOGGER.debug("Getting remote info for id " + id + " and arch " + arch + " and remote " + remote);

    try{

      Optional<FlatpakRefRemoteInfo> quickInfo = getQuickBasicRemoteInfoByRemoteAndArchAndId(remote, arch, id);

      Optional<FlatpakRefRemoteInfo> completeInfo;

      if(quickInfo.isPresent()){

        result = execToString(command);
        completeInfo = parseRemoteInfoString(result);

        if(completeInfo.isPresent()){

          //Wait 300 ms to try to avoid server-side errors
          Thread.sleep(300);

          Optional<String> metadata = getRemoteMetatataByRemoteAndArchAndId(remote, arch, id);
          if(metadata.isPresent()){
            completeInfo.get().setMetadata(metadata.get());
          }

          return completeInfo;
        }
      }

      return Optional.empty();

    }
    catch (Exception e){
      LOGGER.error("There was an error getting remote info for id " + id + " for arch " + arch + " and remote " + remote, e);
      return Optional.empty();
    }

  }



  @Override
  public Optional<String> getRemoteMetatataByRemoteAndArchAndId(String remote, Arch arch, String id) {

    String command = flatpakCommand + " --system remote-info --show-metadata --arch " + arch.toString() + " " + remote + " " + id;
    String result;

    LOGGER.debug("Getting remote metadata for id " + id + "for arch " + arch + " and remote " + remote);

    try{
      result = execToString(command);
      return Optional.ofNullable(result);
    }
    catch (Exception e){
      LOGGER.error("There was an error getting remote metadata for id " + id + " and arch " + arch + " and remote " + remote, e);
      return Optional.empty();
    }

  }


  private Optional<FlatpakRefRemoteInfo> parseBasicRemoteInfoLine(String tabulatedRemoteInfo) {

    FlatpakRefRemoteInfo remoteInfo = new FlatpakRefRemoteInfo();
    String[] columns = tabulatedRemoteInfo.trim().replace("\t\t","\t").split("[\\t]");

    if (columns.length > 3) {

      remoteInfo.setRef(columns[0]);

      remoteInfo.setBranch(getBranchFromRef(remoteInfo.getRef()));
      remoteInfo.setId(getIDFromRef(remoteInfo.getRef()));
      remoteInfo.setArch(getArchFromRef(remoteInfo.getRef()));

      remoteInfo.setShortCommit(columns[1]);
      remoteInfo.setInstalledSize(columns[2]);
      remoteInfo.setDownloadSize(columns[3]);

      if (columns.length > 4 &&
        columns[4] != null && !"".equalsIgnoreCase(columns[4]) && columns[4].startsWith(REMOTE_INFO_EOL_MESSAGE_PREFIX)) {
        remoteInfo.setEndOfLife(columns[4].replace(REMOTE_INFO_EOL_MESSAGE_PREFIX, ""));
        remoteInfo.setEndOfLife(true);
      }
      else{
        remoteInfo.setEndOfLife(false);
      }


      return Optional.of(remoteInfo);
    } else {
      return Optional.empty();
    }

  }


  private String getIDFromRef(String ref){

    String[] fields = ref.split("[/]");

    if(fields.length == 4){
      return fields[1];
    }
    return ref;
  }

  private String getArchFromRef(String ref){

    String[] fields = ref.split("[/]");

    if(fields.length == 4){
      return fields[2];
    }
    return ref;
  }

  private String getBranchFromRef(String ref){

    String[] fields = ref.split("[/]");

    if(fields.length == 4){
      return fields[3];
    }
    return ref;
  }

  private String execToString(String command) throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    CommandLine commandline = CommandLine.parse(command);
    DefaultExecutor exec = new DefaultExecutor();
    exec.setExitValues(FLATPAK_EXIT_VALUES);
    PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
    exec.setStreamHandler(streamHandler);

    ExecuteWatchdog watchdog = new ExecuteWatchdog(20000);
    exec.setWatchdog(watchdog);
    int exitValue = exec.execute(commandline);

    if (exitValue != 0) {

      if(watchdog.killedProcess()){
        // it was killed on purpose by the watchdog
        throw new TimeoutException();
      }
      else {
        throw new Exception(outputStream.toString());
      }

    }

    return(outputStream.toString());
  }

  private Optional<FlatpakRefRemoteInfo> parseRemoteInfoString(String remoteInfoString){

    String[] lines = remoteInfoString.split("[\\r\\n]+");
    String line;

    FlatpakRefRemoteInfo remoteInfo = new FlatpakRefRemoteInfo();

    remoteInfo.setEndOfLife(false);

    int lineCount = 0;

    while(lineCount<lines.length){

      line = lines[lineCount].trim();

      if(line.startsWith(REMOTE_INFO_REF)){
        remoteInfo.setRef(line.replace(REMOTE_INFO_REF, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_ID)){
        remoteInfo.setId(line.replace(REMOTE_INFO_ID, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_ARCH)){
        remoteInfo.setArch(line.replace(REMOTE_INFO_ARCH, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_BRANCH_)){
        remoteInfo.setBranch(line.replace(REMOTE_INFO_BRANCH_, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_COLLECTION_ID)){
        remoteInfo.setCollection(line.replace(REMOTE_INFO_COLLECTION_ID, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_COLLECTION)){
        remoteInfo.setCollection(line.replace(REMOTE_INFO_COLLECTION, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_DATE)){
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");
        remoteInfo.setDate(LocalDateTime.parse(line.replace(REMOTE_INFO_DATE, "").trim(),
          formatter));
      }
      else if(line.startsWith(REMOTE_INFO_SUBJECT)){
        remoteInfo.setSubject(line.replace(REMOTE_INFO_SUBJECT, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_COMMIT)){
        remoteInfo.setCommit(line.replace(REMOTE_INFO_COMMIT, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_PARENT)){
        remoteInfo.setParent(line.replace(REMOTE_INFO_PARENT, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_DOWNLOAD_SIZE)){
        remoteInfo.setDownloadSize(line.replace(REMOTE_INFO_DOWNLOAD_SIZE, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_DOWNLOAD)){
        remoteInfo.setDownloadSize(line.replace(REMOTE_INFO_DOWNLOAD, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_INSTALLED_SIZE)){
        remoteInfo.setInstalledSize(line.replace(REMOTE_INFO_INSTALLED_SIZE, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_INSTALLED)){
        remoteInfo.setInstalledSize(line.replace(REMOTE_INFO_INSTALLED, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_RUNTIME)){
        remoteInfo.setRuntime(line.replace(REMOTE_INFO_RUNTIME, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_SDK)){
        remoteInfo.setSdk(line.replace(REMOTE_INFO_SDK, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_EOL)){
        remoteInfo.setEndOfLife(line.replace(REMOTE_INFO_EOL, "").trim());
        remoteInfo.setEndOfLife(true);
      }
      else if(line.startsWith(REMOTE_INFO_EOL_REBASE)){
        remoteInfo.setEndOfLifeRebase(line.replace(REMOTE_INFO_EOL_REBASE, "").trim());
      }
      else if(line.startsWith(REMOTE_INFO_HISTORY)){
        remoteInfo.setHistory(parseOstreeHistory(remoteInfo, Arrays.copyOfRange(lines, lineCount, lines.length)));
        break;
      }
      lineCount++;
    }

    return Optional.of(remoteInfo);
  }

  private ArrayList<FlatpakRefRemoteInfo> parseOstreeHistory(FlatpakRefRemoteInfo currentRemoteInfo, String[] history) {

    ArrayList<FlatpakRefRemoteInfo> list = new ArrayList<>();
    Optional<FlatpakRefRemoteInfo> currentParsedRemoteInfo;
    String currentParsedRemoteInfoString;

    int lineCount = 0;
    while(lineCount<history.length) {

      if((lineCount + 2 < history.length) &&
          (history[lineCount] != null && history[lineCount].trim().startsWith(REMOTE_INFO_COMMIT) &&
          (history[lineCount + 1] != null && history[lineCount + 1].trim().startsWith(REMOTE_INFO_SUBJECT)) &&
          (history[lineCount + 2] != null && history[lineCount + 2].trim().startsWith(REMOTE_INFO_DATE)))
        ){

        currentParsedRemoteInfoString = history[lineCount].trim() + "\n" + history[lineCount + 1].trim() + "\n" + history[lineCount + 2].trim();
        currentParsedRemoteInfo = parseRemoteInfoString(currentParsedRemoteInfoString);

        // Add the missing data from parent
        if(currentParsedRemoteInfo.isPresent()){
          currentParsedRemoteInfo.get().setRef(currentRemoteInfo.getRef());
          currentParsedRemoteInfo.get().setId(currentRemoteInfo.getId());
          currentParsedRemoteInfo.get().setArch(currentRemoteInfo.getArch());
          currentParsedRemoteInfo.get().setBranch(currentRemoteInfo.getBranch());
          currentParsedRemoteInfo.get().setCollection(currentRemoteInfo.getCollection());
          currentParsedRemoteInfo.get().setEndOfLife(false);
          list.add(currentParsedRemoteInfo.get());
        }
        lineCount = lineCount + 3;
      }
      else {
        lineCount++;
      }

    }

    //Set parent info for child nodes
    for(int i = 0;i<list.size() -1; i++) {
      list.get(i).setParent(list.get(i+1).getCommit());
    }

    //Set history for child nodes
    for(int i = 0;i<list.size() -1; i++) {
      list.get(i).setHistory(new ArrayList<>(list.subList(i+1, list.size())));
    }

    return list;
  }



}
