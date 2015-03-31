/*
 * RSConnectDeploy.java
 *
 * Copyright (C) 2009-15 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.rsconnect.ui;

import java.util.ArrayList;

import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.files.FileSystemItem;
import org.rstudio.core.client.widget.Operation;
import org.rstudio.core.client.widget.OperationWithInput;
import org.rstudio.core.client.widget.ProgressIndicator;
import org.rstudio.core.client.widget.ProgressOperationWithInput;
import org.rstudio.core.client.widget.ThemedButton;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.common.FileDialogs;
import org.rstudio.studio.client.common.GlobalDisplay;
import org.rstudio.studio.client.rsconnect.model.RSConnectAccount;
import org.rstudio.studio.client.rsconnect.model.RSConnectApplicationInfo;
import org.rstudio.studio.client.rsconnect.model.RSConnectDeploymentFiles;
import org.rstudio.studio.client.rsconnect.model.RSConnectDeploymentRecord;
import org.rstudio.studio.client.rsconnect.model.RSConnectPublishResult;
import org.rstudio.studio.client.rsconnect.model.RSConnectPublishSettings;
import org.rstudio.studio.client.rsconnect.model.RSConnectServerOperations;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.workbench.prefs.model.UIPrefs;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JsArray;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.dom.client.Style.FontWeight;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.ChangeEvent;
import com.google.gwt.event.dom.client.ChangeHandler;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.resources.client.ClientBundle;
import com.google.gwt.resources.client.CssResource;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.ui.Anchor;
import com.google.gwt.user.client.ui.CheckBox;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.HTMLPanel;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.InlineLabel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;

public class RSConnectDeploy extends Composite
{
   private static RSConnectDeployUiBinder uiBinder = GWT
         .create(RSConnectDeployUiBinder.class);

   interface RSConnectDeployUiBinder extends UiBinder<Widget, RSConnectDeploy>
   {
   }
   
   public interface DeployStyle extends CssResource
   {
      String accountAnchor();
      String accountList();
      String controlLabel();
      String deployLabel();
      String dropListControl();
      String fileList();
      String firstControlLabel();
      String gridControl();
      String launchCheck();
      String normalStatus();
      String otherStatus();
      String rootCell();
      String source();
      String sourceDestLabels();
      String statusLabel();
      String transferArrow();
      String urlAnchor();
      String wizard();
      String progressPanel();
   }
   
   public interface DeployResources extends ClientBundle
   {  
      ImageResource publishShinyIllustration();
      ImageResource publishRmdIllustration();
      ImageResource publishPlotIllustration();

      @Source("RSConnectDeploy.css")
      DeployStyle style();
   }
   
   public static DeployResources RESOURCES = GWT.create(DeployResources.class);
   
   public RSConnectDeploy(String contentPath, 
                          RSConnectDeploymentRecord fromPrevious,
                          boolean asWizard)
   {
      if (contentPath != null)
      {
         String ext = FileSystemItem.getExtensionFromPath(contentPath)
               .toLowerCase();
         forDocument_ = ext.equals(".rmd") || ext.equals("html");
      }
      else
      {
         forDocument_ = asWizard;
      }

      contentPath_ = contentPath;
      fromPrevious_ = fromPrevious;
      
      // import static/code and single/multiple settings from previous
      // deployment, if we have one
      if (fromPrevious != null)
      {
         asMultipleRmd_ = fromPrevious.getAsMultiple();
         asStatic_ = fromPrevious.getAsStatic();
      }
      
      // inject dependencies 
      RStudioGinjector.INSTANCE.injectMembers(this);
      
      // create UI
      initWidget(uiBinder.createAndBindUi(this));
      style_ = RESOURCES.style();
      
      if (asWizard)
      {
         deployIllustration_.setVisible(false);
         rootPanel_.addStyleName(style_.wizard());
      }
      else
      {
         deployIllustration_.setResource(forDocument_ ?
                              RESOURCES.publishRmdIllustration() :
                              RESOURCES.publishShinyIllustration());
      }
      
      // Invoke the "add account" wizard
      addAccountAnchor_.addClickHandler(new ClickHandler()
      {
         @Override
         public void onClick(ClickEvent event)
         {
            connector_.showAccountWizard(false, new OperationWithInput<Boolean>() 
            {
               @Override
               public void execute(Boolean successful)
               {
                  if (successful)
                  {
                     accountList_.refreshAccountList();
                  }
               }
            });
            
            event.preventDefault();
            event.stopPropagation();
         }
      });

      addFileButton_.setVisible(forDocument_);
      addFileButton_.getElement().getStyle().setMarginLeft(0, Unit.PX);
      addFileButton_.addClickHandler(new ClickHandler()
      {
         @Override
         public void onClick(ClickEvent arg0)
         {
            onAddFileClick();
         }
      });
      
      // If we're loading a previous deployment, hide new app name fields
      if (fromPrevious_ != null)
      {
         newAppPanel_.setVisible(false);
      }
      
   }
   
   @Inject
   public void initialize(RSConnectServerOperations server, 
                          RSAccountConnector connector,    
                          GlobalDisplay display,
                          UIPrefs prefs)
   {
      server_ = server;
      connector_ = connector;
      display_ = display;
      prefs_ = prefs;
      accountList_ = new RSConnectAccountList(server_, display_, false);
      
      // when the account list finishes populating, select the account from the
      // previous deployment if we have one
      accountList_.setOnRefreshCompleted(new Operation() {
         @Override
         public void execute()
         {
            if (fromPrevious_ != null)
            {
               // when re-deploying, select the account used the last time 
               // around
               accountList_.selectAccount(fromPrevious_.getAccount());
            }
            else
            {
               // when doing a first-time publish, select the account the user
               // prefers (currently this just tracks the last account used)
               RSConnectAccount preferred = 
                     prefs_.preferredPublishAccount().getGlobalValue();
               if (preferred != null)
               {
                  accountList_.selectAccount(preferred);
               }
            }
         }
      });
      
      // when the user selects a different account, show the appropriate UI
      addAccountChangeHandler(new ChangeHandler()
      {
         @Override
         public void onChange(ChangeEvent arg0)
         {
            if (fromPrevious_ != null)
            {
               boolean existing = accountList_.getSelectedAccount().equals(
                     fromPrevious_.getAccount());
               appInfoPanel_.setVisible(existing);
               newAppPanel_.setVisible(!existing);
            }
         }
      });
   }
    
   public void setSourceDir(String dir)
   {
      dir = StringUtil.shortPathName(FileSystemItem.createDir(dir), 250);
      deployLabel_.setText(dir);
   }
   
   public void setNewAppName(String name)
   {
      appName_.setText(name);
   }

   public void setDefaultAccount(RSConnectAccount account)
   {
      accountList_.selectAccount(account);
   }
   
   public void setAccountList(JsArray<RSConnectAccount> accounts)
   {
      accountList_.setAccountList(accounts);
   }
   
   public void addFileToList(String path)
   {
      addFile(path, true);
   }
   
   public ArrayList<String> getFileList()
   {
      return getCheckedFileList(true);
   }
   
   public ArrayList<String> getIgnoredFileList()
   {
      return getCheckedFileList(false);
   }
   
   public String getNewAppName()
   {
      return appName_.getText();
   }
   
   public void showAppInfo(RSConnectApplicationInfo info)
   {
      if (info == null)
      {
         appInfoPanel_.setVisible(false);
         newAppPanel_.setVisible(true);
         return;
      }

      urlAnchor_.setText(info.getUrl());
      urlAnchor_.setHref(info.getUrl());
      String status = info.getStatus();
      statusLabel_.setText(status);
      statusLabel_.setStyleName(style_.statusLabel() + " " + 
              (status.equals("running") ?
                    style_.normalStatus() :
                    style_.otherStatus()));

      appInfoPanel_.setVisible(true);
      appDetailsPanel_.setVisible(true);
      newAppPanel_.setVisible(false);
   }
   
   public HandlerRegistration addAccountChangeHandler(ChangeHandler handler)
   {
      return accountList_.addChangeHandler(handler);
   }

   public void setOnDeployEnabled(Command cmd)
   {
      appName_.setOnNameIsValid(cmd);
   }
   
   public void setOnDeployDisabled(Command cmd)
   {
      appName_.setOnNameIsInvalid(cmd);
   }
   
   public DeployStyle getStyle()
   {
      return style_;
   }
   
   public void onActivate(ProgressIndicator indicator)
   {
      populateAccountList(false);
      populateDeploymentFiles(indicator);
   }
   
   public void setContentPath(String contentPath, boolean asMultipleRmd, 
         boolean asStatic)
   {
      contentPath_ = contentPath;
      asMultipleRmd_ = asMultipleRmd;
   }
   
   public void focus()
   {
      appName_.setFocus(true);
   }
   
   public RSConnectPublishResult getResult() 
   {
      // compose the list of files that have been manually added; we want to
      // include all the ones the user added but didn't later uncheck, so
      // cross-reference the list we kept with the one returned by the dialog
      ArrayList<String> deployFiles = getFileList();
      ArrayList<String> additionalFiles = new ArrayList<String>();
      for (String filePath: filesAddedManually_)
      {
         if (deployFiles.contains(filePath))
         {
            additionalFiles.add(filePath);
         }
      }
      
      // if we're redeploying to the same account, use the previous app name;
      // otherwise, read the new name the user's entered
      String appName = fromPrevious_ != null && 
            getSelectedAccount().equals(fromPrevious_.getAccount()) ?
            fromPrevious_.getName() : getNewAppName();
            
      // if this was new content, set this account as the default to use for 
      // new content
      if (fromPrevious_ == null)
      {
         prefs_.preferredPublishAccount().setGlobalValue(getSelectedAccount());
      }
            
      return new RSConnectPublishResult(
            appName, 
            getSelectedAccount(), 
            FileSystemItem.createFile(contentPath_).getParentPathString(), 
            contentPath_, 
            new RSConnectPublishSettings(deployFiles, 
               additionalFiles, 
               getIgnoredFileList(),
               asMultipleRmd_,
               asStatic_));
   }
   
   public boolean isResultValid()
   {
      return appName_.validateAppName();
   }
   
   // Private methods --------------------------------------------------------
   
   private void setFileList(JsArrayString files, 
         ArrayList<String> additionalFiles, ArrayList<String> ignoredFiles)
   {
      if (forDocument_)
      {
         fileChecks_ = new ArrayList<CheckBox>();
      }
      
      // clear existing file list
      fileListPanel_.clear(); 
      for (int i = 0; i < files.length(); i++)
      {
         boolean checked = true;
         boolean add = true;
         
         // if this file is marked ignored, uncheck it
         if (ignoredFiles != null)
         {
            for (int j = 0; j < ignoredFiles.size(); j++)
            {
               if (ignoredFiles.get(j).equals(files.get(i)))
               {
                  checked = false; 
                  break;
               }
            }
         }

         // if this file is marked additional, don't add it twice (we're about
         // to add the additional files separately below)
         if (additionalFiles != null)
         {
            for (int j = 0; j < additionalFiles.size(); j++)
            {
               if (additionalFiles.get(j).equals(files.get(i)))
               {
                  add = false; 
                  break;
               }
            }
         }

         if (add)
         {
            addFile(files.get(i), checked);
         }
      }

      // add any additional files 
      if (additionalFiles != null)
      {
         for (int i = 0; i < additionalFiles.size(); i++)
         {
            addFile(additionalFiles.get(i), true);
         }
      }
   }
   
   private RSConnectAccount getSelectedAccount()
   {
      return accountList_.getSelectedAccount();
   }
   
   private void setPreviousInfo()
   {
      // when the dialog is servicing a redeploy, find information on the
      // content as currently deployed
      if (fromPrevious_ != null)
      {
         appProgressName_.setText(fromPrevious_.getName());
         appProgressPanel_.setVisible(true);
         appInfoPanel_.setVisible(true);

         // get all of the apps deployed from the account to the server
         server_.getRSConnectAppList(
               fromPrevious_.getAccountName(), 
               fromPrevious_.getServer(), 
               new ServerRequestCallback<JsArray<RSConnectApplicationInfo>>()
               {
                  @Override
                  public void onResponseReceived(
                        JsArray<RSConnectApplicationInfo> infos)
                  {
                     // hide server progress
                     appProgressPanel_.setVisible(false);

                     // find an app with the same account, server, and name;
                     // when found, populate the UI with app details
                     for (int i = 0; i < infos.length(); i++)
                     {
                        RSConnectApplicationInfo info = infos.get(i);
                        if (info.getName() == fromPrevious_.getName())
                        {
                           showAppInfo(info);
                           break;
                        }
                     }
                  }
                  @Override
                  public void onError(ServerError error)
                  {
                     // it's okay if we fail here, since the application info
                     // display is purely informative
                     appProgressPanel_.setVisible(false);
                  }
               });
      }
   }
   
   private void populateAccountList(final boolean isRetry)
   {
       server_.getRSConnectAccountList(
            new ServerRequestCallback<JsArray<RSConnectAccount>>()
      {
         @Override
         public void onResponseReceived(JsArray<RSConnectAccount> accounts)
         {
            // if this is our first try, ask the user to connect an account
            // since none are currently connected
            if (accounts.length() == 0 && !isRetry)
            {
               connector_.showAccountWizard(true, 
                     new OperationWithInput<Boolean>() 
               {
                  @Override
                  public void execute(Boolean input)
                  {
                     populateAccountList(true);
                  }
               });
            }
            else
            {
               setAccountList(accounts);
               setPreviousInfo();
            }
         }
         
         @Override
         public void onError(ServerError error)
         {
            display_.showErrorMessage("Error retrieving accounts", 
                                      error.getMessage());
         }
      });
   }
   
   private void populateDeploymentFiles(final ProgressIndicator indicator)
   {
      if (contentPath_ == null)
         return;
      
      // read the parent directory if we're "deploying" a .R file
      final String deployTarget =  
            FileSystemItem.getExtensionFromPath(contentPath_)
            .toLowerCase().equals(".r") ?
         FileSystemItem.createFile(contentPath_).getParentPathString() :
         contentPath_;
      indicator.onProgress("Collecting files...");
      server_.getDeploymentFiles(
            deployTarget,
            asMultipleRmd_,
            new ServerRequestCallback<RSConnectDeploymentFiles>()
            {
               @Override 
               public void onResponseReceived(RSConnectDeploymentFiles files)
               {
                  if (files.getDirSize() > files.getMaxSize())
                  {
                     display_.showErrorMessage("Directory Too Large", 
                           "The directory to be deployed (" + deployTarget + ") " +
                           "exceeds the maximum deployment size, which is " +
                           StringUtil.formatFileSize(files.getMaxSize()) + "." +
                           " Consider creating a new directory containing " + 
                           "only the content you wish to deploy.");
                  }
                  else
                  {
                     setFileList(files.getDirList(), 
                           fromPrevious_ != null ?
                                 fromPrevious_.getAdditionalFiles() : null, 
                           fromPrevious_ != null ? 
                                 fromPrevious_.getIgnoredFiles() : null);
                     setPrimaryFile(
                           FileSystemItem.createFile(contentPath_).getName());
                  }
                  indicator.clearProgress();
               }
               @Override
               public void onError(ServerError error)
               {
                  // we'll just show an empty list in the failure case
                  indicator.clearProgress();
               }
            });
      
   }

   private void addFile(String path, boolean checked)
   {
      if (forDocument_)
      {
         CheckBox fileCheck = new CheckBox(path);
         fileCheck.setValue(checked);
         fileListPanel_.add(fileCheck);
         fileChecks_.add(fileCheck);
      }
      else
      {
         fileListPanel_.add(new Label(path));
      }
   }
   
   private ArrayList<String> getCheckedFileList(boolean checked)
   {
      ArrayList<String> files = new ArrayList<String>();
      if (fileChecks_ == null)
         return files;
      for (int i = 0; i < fileChecks_.size(); i++)
      {
         if (fileChecks_.get(i).getValue() == checked)
         {
            files.add(fileChecks_.get(i).getText());
         }
      }
      return files;
   }
   
   private void onAddFileClick()
   {
      FileDialogs dialogs = RStudioGinjector.INSTANCE.getFileDialogs();
      final FileSystemItem sourceDir = 
            FileSystemItem.createFile(contentPath_).getParentPath();
      dialogs.openFile("Select File", 
            RStudioGinjector.INSTANCE.getRemoteFileSystemContext(), 
            sourceDir, 
            new ProgressOperationWithInput<FileSystemItem>()
            {
               @Override
               public void execute(FileSystemItem input, 
                                   ProgressIndicator indicator)
               {
                  if (input != null)
                  {
                     String path = input.getPathRelativeTo(sourceDir);
                     if (path == null)
                     {
                        display_.showMessage(GlobalDisplay.MSG_INFO, 
                              "Cannot Add File", 
                              "Only files in the same folder as the " +
                              "document (" + sourceDir + ") or one of its " +
                              "sub-folders may be added.");
                        return;
                     }
                     else
                     {
                        // see if the file is already in the list (we don't 
                        // want to duplicate an existing entry)
                        ArrayList<String> files = getFileList();
                        for (String file: files)
                        {
                           if (file.equals(path))
                           {
                              indicator.onCompleted();
                              return;
                           }
                        }
                        addFileToList(path);
                        filesAddedManually_.add(path);
                     }
                  }
                  indicator.onCompleted();
               }
            });
   }

   private void setPrimaryFile(String path)
   {
      if (fileChecks_ == null)
         return;

      for (int i = 0; i < fileChecks_.size(); i++)
      {
         CheckBox fileCheck = fileChecks_.get(i);
         if (fileCheck.getText().equals(path))
         {
            // don't allow the user to unselect the primary file
            fileCheck.setEnabled(false);
            
            // make this bold and move it to the top
            fileCheck.getElement().getStyle().setFontWeight(FontWeight.BOLD);
            fileListPanel_.remove(fileCheck);
            fileListPanel_.insert(fileCheck, 0);
         }
      }
   }
   
   
   @UiField Image deployIllustration_;
   @UiField Anchor urlAnchor_;
   @UiField Anchor addAccountAnchor_;
   @UiField Label nameLabel_;
   @UiField InlineLabel statusLabel_;
   @UiField(provided=true) RSConnectAccountList accountList_;
   @UiField HTMLPanel newAppPanel_;
   @UiField HTMLPanel appInfoPanel_;
   @UiField HTMLPanel appDetailsPanel_;
   @UiField HTMLPanel appProgressPanel_;
   @UiField InlineLabel appProgressName_;
   @UiField VerticalPanel fileListPanel_;
   @UiField InlineLabel deployLabel_;
   @UiField ThemedButton addFileButton_;
   @UiField HTMLPanel rootPanel_;
   @UiField AppNameTextbox appName_;
   @UiField Grid mainGrid_;
   
   private ArrayList<CheckBox> fileChecks_;
   private ArrayList<String> filesAddedManually_ = 
         new ArrayList<String>();
   
   private RSConnectServerOperations server_;
   private GlobalDisplay display_;
   private RSAccountConnector connector_;
   private UIPrefs prefs_;
   
   private String contentPath_;
   private boolean asMultipleRmd_;
   private boolean asStatic_;

   private final DeployStyle style_;
   private final boolean forDocument_;
   private final RSConnectDeploymentRecord fromPrevious_;
}
