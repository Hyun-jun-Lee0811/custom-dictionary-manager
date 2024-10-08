package com.github.Hyun_jun_Lee0811.dictionary.service;

import static com.github.Hyun_jun_Lee0811.dictionary.type.ErrorCode.*;

import com.github.Hyun_jun_Lee0811.dictionary.client.WordnikClient;
import com.github.Hyun_jun_Lee0811.dictionary.expection.ErrorResponse;
import com.github.Hyun_jun_Lee0811.dictionary.model.UserThinkForm;
import com.github.Hyun_jun_Lee0811.dictionary.model.dto.UserThinkDTO;
import com.github.Hyun_jun_Lee0811.dictionary.model.dto.WordDefinitionDto;
import com.github.Hyun_jun_Lee0811.dictionary.model.entity.User;
import com.github.Hyun_jun_Lee0811.dictionary.model.entity.UserThink;
import com.github.Hyun_jun_Lee0811.dictionary.model.entity.WordBook;
import com.github.Hyun_jun_Lee0811.dictionary.repository.UserThinkRepository;
import com.github.Hyun_jun_Lee0811.dictionary.repository.UserRepository;
import com.github.Hyun_jun_Lee0811.dictionary.repository.WordBookRepository;
import com.github.Hyun_jun_Lee0811.dictionary.security.JwtAuthenticationFilter;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserThinkService {

  private final UserThinkRepository userThinkRepository;
  private final UserRepository userRepository;
  private final WordBookRepository wordBookRepository;
  private final WordnikClient wordnikClient;
  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private static final int MAX_USER_THINKS = 100;

  public void saveUserThink(UserThinkForm userThinkForm) {

    jwtAuthenticationFilter.isUserAuthenticated(userThinkForm.getUsername());
    checkUserThinkLimit(getUserIdByUsername(userThinkForm.getUsername()));

    userThinkRepository.save(UserThink.builder()
        .userId(getUserIdByUsername(userThinkForm.toDTO().getUsername()))
        .wordId(determineWordId(
            userThinkForm.toDTO(),
            wordnikClient.getDefinitions(userThinkForm.getWord())))

        .word(userThinkForm.toDTO().getWord())
        .userThink(userThinkForm.toDTO().getUserThink())
        .isPrivate(userThinkForm.toDTO().getIsPrivate())
        .createdAt(LocalDateTime.now())
        .build());

    updateWordBook(getUserIdByUsername(userThinkForm.getUsername()),
        userThinkForm.getWord(), userThinkForm.getWordId());
  }

  private void updateWordBook(Long userId, String word, String wordId) {
    WordBook wordBook = wordBookRepository.findByUserIdAndWord(userId, word)
        .orElse(WordBook.builder()
            .userId(userId)
            .word(word)
            .wordId(wordId)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build());

    wordBookRepository.save(wordBook);
  }

  public Page<UserThinkDTO> getUserThoughts(String username, Pageable pageable) {
    return userThinkRepository.findByUserId(getUserIdByUsername(username), pageable)
        .map(this::mapToDTO);
  }

  public List<UserThinkDTO> getPublicThoughts(String username) {
    return userThinkRepository.findByUserIdAndIsPrivate(getUserIdByUsername(username), false)
        .stream()
        .map(this::mapToDTO)
        .collect(Collectors.toList());
  }

  public List<UserThinkDTO> getUserThinksByWord(String username, String wordId) {
    List<UserThink> userThinks = userThinkRepository.findByUserIdAndWordId(
        getUserIdByUsername(username), wordId);

    if (userThinks.isEmpty()) {
      throw new ErrorResponse(NO_USERTHINKS_FOUND_OR_ACCESS_DENIED);
    }

    return userThinks.stream()
        .filter(
            think -> !think.getIsPrivate() || jwtAuthenticationFilter.isUserAuthenticated(username))
        .map(this::mapToDTO)
        .collect(Collectors.toList());
  }

  public void changeUserThink(Long id, UserThinkForm userThinkForm) {
    UserThink findUserThink = userThinkRepository.findById(id)
        .orElseThrow(() -> new ErrorResponse(NO_USERTHINKS_FOUND_OR_ACCESS_DENIED));

    String findUsername = userRepository.findById(findUserThink.getUserId())
        .map(User::getUsername)
        .orElseThrow(() -> new ErrorResponse(USER_NOT_EXIT));

    if (!findUsername.equals(userThinkForm.getUsername())) {
      throw new ErrorResponse(USER_NOT_AUTHENTICATED);
    }

    findUserThink.setUserThink(userThinkForm.getUserThink());
    findUserThink.setUpdatedAt(LocalDateTime.now());
    userThinkRepository.save(findUserThink);
  }

  public void deleteUserThink(String username, Long id) {
    UserThink userThink = userThinkRepository.findById(id)
        .orElseThrow(() -> new ErrorResponse(NO_USERTHINKS_FOUND_OR_ACCESS_DENIED));

    if (!jwtAuthenticationFilter.isUserAuthenticated(username)) {
      throw new ErrorResponse(USER_NOT_AUTHENTICATED);
    }

    if (!userThink.getUserId().equals(getUserIdByUsername(username))) {
      throw new ErrorResponse(NO_USERTHINKS_FOUND_OR_ACCESS_DENIED);
    }
    userThink.setDeletedAt(LocalDateTime.now());
    userThinkRepository.delete(userThink);
  }

  // 사용자의 UserThink 개수 제한을 확인하는 메서드
  private void checkUserThinkLimit(Long userId) {
    long userThinkCount = userThinkRepository.countByUserId(userId);

    if (userThinkCount >= MAX_USER_THINKS) {
      throw new ErrorResponse(MAX_USER_THINKS_EXCEEDED);
    }
  }

  //사용자 이름을 통해 아이디 가져오는 메서드
  private Long getUserIdByUsername(String username) {
    return userRepository.findByUsername(username)
        .map(User::getUserId)
        .orElseThrow(() -> new ErrorResponse(USER_NOT_EXIT));
  }

  // 단어 ID를 결정하여 반환하는 메서드 -> 사용자가 단어 ID를 미입력하면 입력한 단어의 첫번째 아이디로 적용
  private String determineWordId(UserThinkDTO userThinkDTO, List<WordDefinitionDto> definitions) {
    if (userThinkDTO.getWordId() != null && !userThinkDTO.getWordId().trim().isEmpty()) {
      return validateAndReturnWordId(userThinkDTO.getWordId(), definitions);

    } else {
      return selectFirstValidWordId(definitions);
    }
  }

  // 제공된 단어 ID가 유효한지 확인하고 반환하는 메서드
  private String validateAndReturnWordId(String wordId, List<WordDefinitionDto> definitions) {
    boolean validId = definitions.stream()
        .anyMatch(definition -> definition.getId() != null && definition.getId().equals(wordId));

    if (validId) {
      return wordId;
    } else {
      throw new ErrorResponse(EXAMPLES_API_CLIENT_ERROR);
    }
  }

  // 단의 정의 목록에서 첫 번째 유효한 단어 ID를 선택
  private String selectFirstValidWordId(List<WordDefinitionDto> definitions) {
    return definitions.isEmpty() ? USER_ID_NOT_PROVIDED.getMessage() :
        definitions.stream()
            .map(WordDefinitionDto::getId)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(USER_ID_NOT_PROVIDED.getMessage());
  }

  // UserThink 엔티티를 UserThinkDTO로 변환하는 메서드
  private UserThinkDTO mapToDTO(UserThink think) {
    String username = userRepository.findById(think.getUserId())
        .map(User::getUsername)
        .orElse(ID_NOT_EXIT.getMessage());

    return UserThinkDTO.builder()
        .id(think.getId())
        .username(username)
        .wordId(think.getWordId())
        .word(think.getWord())
        .userThink(think.getUserThink())
        .isPrivate(think.getIsPrivate())
        .build();
  }
}